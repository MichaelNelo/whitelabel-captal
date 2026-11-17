package whitelabel.captal.mcp.tools

import cats.data.NonEmptyChain
import cats.syntax.traverse.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.commands.{
  AwaitedFile,
  ConfirmVideoFileCommand,
  FileConfirmation,
  RegisterVideoCommand,
  RegisteredVideo,
  ReinstateVideoCommand,
  RetireVideoCommand,
  StartFileReplacementCommand,
  UpdateVideoCommand,
  VideoCorrection,
  VideosInRotation
}
import whitelabel.captal.core.application.queries.{
  PendingUploadReport,
  PendingUploadsQuery,
  UploadUrlQuery,
  VideoReport,
  VideosOfQuery,
  WaitingUpload
}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.survey.authoring.QuestionSpec
import whitelabel.captal.core.survey.{AdvertiserId, Id as SurveyId}
import whitelabel.captal.core.video
import whitelabel.captal.core.video.VideoType
import whitelabel.captal.core.video.authoring.{Error as VideoError, FileId, VideoKind}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.apps.UploadUi
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{OperatorContext, VideoStatus}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.{reads, runs, runsEither}
import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** The videos, through `core`'s video authoring commands and the `VideosOf`, `PendingUploads` and
  * `UploadUrl` queries. What is left here is the wire, and storage: signing the URL a file is
  * uploaded to, checking it arrived before it is confirmed, and deleting the files nobody plays.
  */
object VideoTools:
  private[mcp] val UploadValidFor: Duration = 30.minutes

  final case class CreateVideoRequest(
      @description(
        "Which location shows the video. The survey created alongside it inherits this, which is how advertiser questions get scoped.")
      slug: String,
      @description("Whose video this is. Call list_advertisers for the ids.")
      advertiserId: String,
      @description("Shown above the player, one entry per locale.")
      title: List[LocalizedText],
      @description("Optional blurb under the title.")
      description: Option[List[LocalizedText]],
      @description(
        "How long the guest must watch before the survey unlocks. Omit for the whole video. The length itself is not asked for here — it is read from the file at confirm_video_upload, so a value above it is lowered to match rather than refused.")
      minWatchSeconds: Option[Int],
      @description(
        "Weights this video against the advertiser's others. Defaults to 10; zero means never drawn.")
      priority: Option[Int],
      @description(
        "Asked after the video. At least one — a video whose survey has nothing left to ask is never served.")
      questions: List[VideoQuestion],
      @description(
        "The client's own campaign id for this video, if they gave one. Opaque to us: nothing here parses, validates or joins on it — it exists so a client can match our reporting against their campaign. Ask the operator; omit it if there is none. Promos have no equivalent, since a promo is the venue's own.")
      productCampaignId: Option[String])

  final case class VideoQuestion(
      @description(
        "The wording, one entry per locale. Ask the operator; nothing else can supply it.")
      text: List[LocalizedText],
      @description(
        "How the answer is collected, and what constrains it. `kind` picks the variant and decides which other fields are allowed.")
      answer: AnswerFormat,
      @description("Whether an answer is mandatory. Defaults to true.")
      required: Option[Boolean],
      @description("Points awarded for answering. Defaults to 10.")
      points: Option[Int])

  final case class CreateVideoResponse(
      @description("What confirm_video_upload takes once the file is in place.")
      videoId: String,
      @description("The survey created alongside, asked after this video.")
      surveyId: String,
      @description("The questions created, in the order given.")
      questionIds: List[String],
      @description(
        "PUT the file here. The signature does not bind a Content-Type — there was no file to take one from when it was signed — so send the file's own; confirm_video_upload checks what arrived.")
      uploadUrl: String,
      @description(
        "How long the signed URL stays valid. After that, call resume_video_upload for a fresh one — not create_advertiser_video, which would make a second video, survey and set of questions for one file.")
      expiresInSeconds: Long,
      @description(
        "The upload page for this video, when the host renders MCP Apps. Otherwise PUT to uploadUrl directly.")
      uploadPageUri: String,
      @description(
        "The video stays inactive and out of rotation until confirm_video_upload finds the file.")
      active: Boolean)

  final case class CreatePromoRequest(
      @description(
        "Which location shows it. A promo belongs to one location, like any other video.")
      slug: String,
      @description("Shown above the player, one entry per locale.")
      title: List[LocalizedText],
      @description("Optional blurb under the title.")
      description: Option[List[LocalizedText]],
      @description(
        "How long the guest must watch before moving on. Omit for the whole video. The length itself is read from the file at confirm_video_upload.")
      minWatchSeconds: Option[Int],
      @description(
        "Where it sits in the rotation. Promos are served in ascending order of priority and then creation date — not weighted at random like advertiser videos — so this is a position, not a weight. Defaults to 10.")
      priority: Option[Int],
      @description(
        "Seconds before the same guest may be shown this again. Omit for no limit. Only promos rotate, so this is the one video kind the field means anything for.")
      noRepeatSeconds: Option[Int],
      @description(
        "What the guest is asked after watching. Omit or leave empty for a promo that only plays — unlike an advertiser's video, a promo is served whether or not it has questions left, so a promo with none is a valid thing to want.")
      questions: Option[List[VideoQuestion]])

  final case class CreatePromoResponse(
      @description("What confirm_video_upload takes once the file is in place.")
      videoId: String,
      @description(
        "PUT the file here. The signature binds no Content-Type — there was no file to take one from — so send the file's own.")
      uploadUrl: String,
      @description("How long the signed URL stays valid.")
      expiresInSeconds: Long,
      @description("The upload page for this video, when the host renders MCP Apps.")
      uploadPageUri: String,
      @description(
        "A promo stays out of rotation until confirm_video_upload finds the file, so an abandoned upload costs nothing.")
      active: Boolean,
      @description(
        "The survey created for it, absent when no questions were given. Pass it to create_question to extend the survey later.")
      surveyId: Option[String],
      @description("The questions written, in the order given.")
      questionIds: List[String])

  final case class ListVideosRequest(
      @description("Narrow to one location. Omit for every location.")
      slug: Option[String],
      @description(
        "Include videos taken out of rotation. Off by default: a retired video keeps its file and its answers, so the list is usually about what is running.")
      includeRetired: Option[Boolean])

  final case class VideoView(
      @description("Addresses it in resume_video_upload and confirm_video_upload.")
      videoId: String,
      @description("Its title, as stored. Falls back to the id for a video created without one.")
      title: String,
      @description("Where it is shown.")
      slug: String,
      @description(
        "`advertiser` for an advertiser's video, `promo` for one of the venue's own. Stored as `ad` and `promo`.")
      kind: String,
      @description("Whose it is. Absent for a promo, which belongs to no advertiser.")
      advertiser: Option[String],
      @description(
        "`pending` is registered with no file and invisible to guests; `active` is in rotation; `retired` keeps its file and its answers but is never drawn.")
      status: String,
      @description(
        "The length of the file it plays. Zero for a pending video, whose length is read from the file at confirm.")
      durationSeconds: Int,
      @description("When the survey unlocks.")
      minWatchSeconds: Int,
      @description("Weights it against the others. Zero means never drawn.")
      priority: Int,
      @description(
        "True while a new file started with replace_video_file waits to be uploaded or confirmed. The video keeps playing its current file meanwhile; resume_video_upload reopens the page.")
      replacing: Boolean)

  final case class ListVideosResponse(
      @description("Active first, then pending, then retired; by descending priority within each.")
      videos: List[VideoView])

  final case class PendingUploadsRequest(
      @description(
        "Narrow to one location's videos. Omit for every location, which is usually what you want: an abandoned upload is easiest to miss at the location nobody is looking at.")
      slug: Option[String])

  final case class PendingUpload(
      @description("Pass this to resume_video_upload.")
      videoId: String,
      @description("Its title, as stored. Falls back to the id for a video created without one.")
      title: String,
      @description("Where it would be shown.")
      slug: String,
      @description("When the video was registered. The oldest are the likeliest to be abandoned.")
      createdAt: String)

  final case class PendingUploadsResponse(
      @description("Every video registered but still waiting for its file, oldest first.")
      videos: List[PendingUpload])

  final case class ResumeUploadRequest(
      @description("The video to upload. Call list_pending_uploads for what is waiting.")
      videoId: String)

  final case class ReplaceFileRequest(
      @description("The video whose file to replace. Call list_videos for the ids.")
      videoId: String)

  final case class ResumeUploadResponse(
      @description("The video the page was opened for.")
      videoId: String,
      @description("Its title, as stored.")
      title: String,
      @description("Where it will be shown.")
      slug: String,
      @description(
        "PUT the file here if the page did not render. Signed fresh on this call, so an older link having lapsed does not matter.")
      uploadUrl: String,
      @description("How long this signature lasts.")
      expiresInSeconds: Long)

  final case class ConfirmUploadRequest(
      @description(
        "The video whose upload to check. Comes from create_advertiser_video, create_promo_video, resume_video_upload, replace_video_file or list_pending_uploads.")
      videoId: String,
      @description(
        "The file's length in whole seconds. The upload page reads it from the file itself and sends it; supply it by hand only when the page could not — some WebM files carry no duration in their header. It is not a preference: a wrong value makes the player count down to the wrong place.")
      durationSeconds: Int)

  final case class ConfirmUploadResponse(
      @description("The video confirmed.")
      videoId: String,
      @description(
        "Whether the video is in rotation. A pending video enters it here; a retired one whose file was replaced stays retired until set_video_active.")
      active: Boolean,
      @description("Where the portal will fetch it from.")
      videoUrl: String,
      @description("The length recorded, as read from the file.")
      durationSeconds: Int,
      @description(
        "When the survey unlocks. Equals the duration unless create_advertiser_video asked for less.")
      minWatchSeconds: Int,
      @description(
        "Present when the requested minWatchSeconds was longer than the video and was lowered to its length. Not an error — a survey that unlocks after the video ends never unlocks at all.")
      minWatchAdjustedFrom: Option[Int],
      @description(
        "True when this file took over from one the video was already playing (replace_video_file). Guests given the previous file keep playing it.")
      replaced: Boolean,
      @description(
        "Replaced files of this video deleted now, because nobody was playing them. One still being played is deleted when its last playback ends.")
      filesDeleted: Int)

  /** What a video can be corrected to after it exists.
    *
    * Not the file and not the location: the file is replaced with `replace_video_file`, and the
    * location is in the file's key and in the survey hanging off it, so moving one would orphan
    * both.
    *
    * `durationSeconds` is absent for the same reason `create_advertiser_video` does not take it: it
    * is read from the file, and a hand-supplied value is a countdown that ends in the wrong place.
    * `minWatchSeconds` is here, and is re-checked against the stored duration.
    */
  final case class UpdateVideoRequest(
      @description("Which video. Call list_videos for the ids.")
      videoId: String,
      @description(
        "Corrected title, one entry per locale. Only the locales given are rewritten. Ask the operator — never invent copy a guest will read.")
      title: Option[List[LocalizedText]],
      @description("Corrected blurb under the title. Same per-locale rules as `title`.")
      description: Option[List[LocalizedText]],
      @description(
        "How long the guest must watch. Lowered to the video's length if it exceeds it, the same way confirm_video_upload does — a survey that unlocks after the video ends never unlocks.")
      minWatchSeconds: Option[Int],
      @description("Rotation weight for an advertiser's video, or position for a promo.")
      priority: Option[Int],
      @description(
        "Seconds before the same guest sees it again. Only means anything for a promo; setting it on an advertiser's video is accepted and ignored by the selection.")
      noRepeatSeconds: Option[Int],
      @description(
        "The client's own campaign id. Pass an empty string to clear it. Opaque to us — nothing parses or joins on it.")
      productCampaignId: Option[String])

  final case class UpdateVideoResponse(
      @description("The video changed.")
      videoId: String,
      @description(
        "Which fields this call actually wrote. Empty means the request asked for nothing.")
      updated: List[String],
      @description(
        "Present when the requested minWatchSeconds was longer than the video and was lowered to its length.")
      minWatchAdjustedFrom: Option[Int])

  final case class SetVideoActiveRequest(
      @description("Which video to retire or bring back.")
      videoId: String,
      @description(
        "False retires it: the file stays, the answers to its survey keep resolving, and the rotation stops drawing it. True returns it to rotation.")
      active: Boolean)

  final case class SetVideoActiveResponse(
      @description("The video changed.")
      videoId: String,
      @description("Its status after the change — `active` or `retired`.")
      status: String,
      @description(
        "Videos still in rotation at this location. Zero means a guest gets past identification and is shown nothing.")
      activeAtLocation: Int)

  given Schema[UpdateVideoRequest] = DeriveSchema.gen
  given Schema[UpdateVideoResponse] = DeriveSchema.gen
  given Schema[SetVideoActiveRequest] = DeriveSchema.gen
  given Schema[SetVideoActiveResponse] = DeriveSchema.gen
  given Schema[CreatePromoRequest] = DeriveSchema.gen
  given Schema[CreatePromoResponse] = DeriveSchema.gen
  given Schema[ListVideosRequest] = DeriveSchema.gen
  given Schema[VideoView] = DeriveSchema.gen
  given Schema[ListVideosResponse] = DeriveSchema.gen
  given Schema[PendingUploadsRequest] = DeriveSchema.gen
  given Schema[PendingUpload] = DeriveSchema.gen
  given Schema[PendingUploadsResponse] = DeriveSchema.gen
  given Schema[VideoQuestion] = DeriveSchema.gen
  given Schema[CreateVideoRequest] = DeriveSchema.gen
  given Schema[CreateVideoResponse] = DeriveSchema.gen
  given Schema[ResumeUploadRequest] = DeriveSchema.gen
  given Schema[ReplaceFileRequest] = DeriveSchema.gen
  given Schema[ResumeUploadResponse] = DeriveSchema.gen
  given Schema[ConfirmUploadRequest] = DeriveSchema.gen
  given Schema[ConfirmUploadResponse] = DeriveSchema.gen

  private def videoIdOf(raw: String): IO[String, video.Id] = ZIO
    .fromOption(video.Id.fromString(raw.trim))
    .orElseFail(
      Refusals.describe(AppError.VideoAuthoring(NonEmptyChain.one(VideoError.VideoNotFound(raw)))))

  /** The questions asked after a video, each with its defaults, or why one cannot be read. */
  private def questionsOf(questions: List[VideoQuestion]): IO[String, List[QuestionSpec]] = ZIO
    .fromEither(
      questions
        .zipWithIndex
        .traverse: (question, index) =>
          SurveyTools
            .specOf(
              question.text,
              question.answer,
              Nil,
              Nil,
              question.required.getOrElse(true),
              question.points.getOrElse(10),
              None)
            .left
            .map(refusal => s"Question ${index + 1}: $refusal"))

  private def signed(key: String): ZIO[AssetStore, String, String] = ZIO.serviceWithZIO[AssetStore](
    _.presignPut(key, UploadValidFor))

  /** The command registering a video of `kind` from what every registration is asked. */
  private def registering(
      slug: String,
      kind: VideoKind,
      title: List[LocalizedText],
      description: Option[List[LocalizedText]],
      minWatchSeconds: Option[Int],
      priority: Option[Int],
      questions: List[VideoQuestion]): IO[String, RegisterVideoCommand] =
    for
      parsed <- Refusals.slugOf(slug)
      specs  <- questionsOf(questions)
      now    <- Clock.instant
    yield RegisterVideoCommand(
      video.Id.generate,
      parsed,
      kind,
      LocalizedText.toCore(title),
      LocalizedText.toCore(description.getOrElse(Nil)),
      minWatchSeconds,
      priority.getOrElse(10),
      SurveyId.generate,
      specs,
      now
    )

  def create: McpToolHandlerR[OperatorContext & AssetStore & VideoFlows.RegisterVideo] = McpTool(
    "create_advertiser_video")
    .description(
      "Register an advertiser video together with the survey shown after it, and hand back a URL to upload the file to. Does not ask for the video's length: that is read from the file at confirm_video_upload, so do not ask the operator for it. The video stays out of rotation until confirm_video_upload finds the file, so an abandoned upload costs nothing. Call read_skill('add-video') first.")
    .requireScopes(Auth.ContentWrite)
    .runs((in: CreateVideoRequest) =>
      for
        advertiser <- ZIO
          .fromOption(AdvertiserId.fromString(in.advertiserId.trim))
          .orElseFail(s"No advertiser with id '${in.advertiserId}'. Call list_advertisers.")
        command <- registering(
          in.slug,
          VideoKind.Advertiser(advertiser, in.productCampaignId.map(_.trim).filter(_.nonEmpty)),
          in.title,
          in.description,
          in.minWatchSeconds,
          in.priority,
          in.questions
        )
      yield command): (_, registered: RegisteredVideo) =>
      signed(registered.key).map: url =>
        CreateVideoResponse(
          videoId = registered.video.asString,
          surveyId = registered.survey.fold("")(_.asString),
          questionIds = registered.questions.map(_.asString),
          uploadUrl = url,
          expiresInSeconds = UploadValidFor.toSeconds,
          uploadPageUri = s"${UploadUi.Uri}/${registered.video.asString}",
          active = false
        )
    // The static page, not a per-video template. `_meta.ui.resourceUri` is declared before any call
    // has produced a video, and `CallToolResult` carries no `_meta` to correct it afterwards — a
    // template here is read literally and resolves to nothing. The page learns which video it is
    // from the result of the call that opened it.
    .withUi(UploadUi.AppUri)

  /** Promo videos: no advertiser, optional survey, served in order (not at random). */
  val createPromo: McpToolHandlerR[OperatorContext & AssetStore & VideoFlows.RegisterVideo] =
    McpTool("create_promo_video")
      .description(
        "Register a video of the venue's own — not an advertiser's — and hand back a URL to upload the file to. No advertiser; a survey only if you pass questions, since a promo is served whether or not it has any left to ask. Does not ask for the video's length, which is read from the file at confirm_video_upload. The video stays out of rotation until that call finds the file.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: CreatePromoRequest) =>
        registering(
          in.slug,
          VideoKind.Promo(in.noRepeatSeconds),
          in.title,
          in.description,
          in.minWatchSeconds,
          in.priority,
          in.questions.getOrElse(Nil))): (_, registered: RegisteredVideo) =>
        signed(registered.key).map: url =>
          CreatePromoResponse(
            videoId = registered.video.asString,
            uploadUrl = url,
            expiresInSeconds = UploadValidFor.toSeconds,
            uploadPageUri = s"${UploadUi.Uri}/${registered.video.asString}",
            active = false,
            surveyId = registered.survey.map(_.asString),
            questionIds = registered.questions.map(_.asString)
          )
      .withUi(UploadUi.AppUri)

  private def viewOf(report: VideoReport): VideoView = VideoView(
    videoId = report.id,
    title = report.title.getOrElse(report.id),
    slug = report.slug.getOrElse("—"),
    // "advertiser" (not "ad"): matches the field name and entity terminology.
    kind =
      report.videoType match
        case VideoType.Ad =>
          "advertiser"
        case VideoType.Promo =>
          "promo"
    ,
    advertiser = report.advertiser,
    status = VideoStatus.stored(report.status),
    durationSeconds = report.durationSeconds.getOrElse(0),
    minWatchSeconds = report.minWatchSeconds,
    priority = report.priority,
    replacing = report.replacing
  )

  private def optionalSlug(raw: Option[String]): IO[String, Option[Slug]] =
    ZIO.foreach(raw.map(_.trim).filter(_.nonEmpty))(Refusals.slugOf)

  val list: McpToolHandlerR[Queries.VideosOf] =
    McpTool("list_videos")
      .description(
        "Every video a location plays, with its status, length and rotation weight. Promos and advertiser videos alike. Retired ones are left out unless asked for.")
      .readOnly
      .reads((in: ListVideosRequest) =>
        optionalSlug(in.slug).map(VideosOfQuery(_, in.includeRetired.getOrElse(false))))(
        (_, reports: List[VideoReport]) => ZIO.succeed(ListVideosResponse(reports.map(viewOf))))

  private def pendingOf(upload: PendingUploadReport) = PendingUpload(
    videoId = upload.id,
    title = upload.title.getOrElse(upload.id),
    slug = upload.slug.getOrElse("—"),
    createdAt = upload.createdAt)

  /** Every video waiting for its first file, for the resources that list them. A listing is not
    * refused, so a failure here is a defect.
    */
  private[mcp] def pending: ZIO[Queries.PendingUploads, Nothing, List[PendingUpload]] = ZIO
    .serviceWithZIO[Queries.PendingUploads](Commands.ask(_, PendingUploadsQuery(None))())
    .orDieWith(IllegalStateException(_))
    .map(_.map(pendingOf))

  val listPending: McpToolHandlerR[Queries.PendingUploads] =
    McpTool("list_pending_uploads")
      .description(
        "Videos registered but still waiting for their file, oldest first. Each one has a survey and questions already written and is invisible to guests until the file arrives, so finishing one is cheaper than creating another. Pass a videoId to resume_video_upload to open its page.")
      .readOnly
      .reads((in: PendingUploadsRequest) => optionalSlug(in.slug).map(PendingUploadsQuery(_)))(
        (_, waiting: List[PendingUploadReport]) =>
          ZIO.succeed(PendingUploadsResponse(waiting.map(pendingOf))))

  /** The file the video `videoId` waits for, as `UploadUrl` reads it; the upload ticket resource
    * signs for the same file the tools do.
    */
  private[mcp] def awaited(videoId: String): ZIO[Queries.UploadUrl, String, WaitingUpload] = ZIO
    .serviceWithZIO[Queries.UploadUrl](Commands.ask(_, UploadUrlQuery(videoId))())

  /** Checks the file arrived, by its key, before it is confirmed: storage stays out of `core`. The
    * file confirmed is the one checked, so one that another replaced meanwhile is refused.
    */
  val confirm: McpToolHandlerR[
    AssetStore & Queries.UploadUrl & OperatorContext & VideoFlows.ConfirmVideoFile &
      VideoFlows.CollectReplacedFiles] =
    McpTool("confirm_video_upload")
      .description(
        "Check the file arrived, record its length, and make it the file the video plays. A pending video enters rotation here; a replaced file takes over from the previous one, which is deleted once nobody is still playing it. Refuses when the object is not there, which is what an abandoned upload looks like. The upload page calls this itself with the duration it read from the file.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: ConfirmUploadRequest) =>
        for
          upload  <- awaited(in.videoId)
          present <- ZIO.serviceWithZIO[AssetStore](_.exists(upload.key))
          _       <-
            ZIO.when(!present)(
              ZIO.fail(
                s"Nothing was uploaded to ${upload
                    .key}. The signed URL may have lapsed — call resume_video_upload for a fresh one."))
          id  <- videoIdOf(upload.video)
          now <- Clock.instant
        yield ConfirmVideoFileCommand(id, upload.file, in.durationSeconds, now)):
        (in, confirmed: FileConfirmation) =>
          for
            id      <- videoIdOf(in.videoId)
            deleted <- VideoFileCollector.collect(Some(id))
            url     <- ZIO.serviceWith[AssetStore](_.publicUrl(confirmed.key))
          yield ConfirmUploadResponse(
            videoId = id.asString,
            active = confirmed.active,
            videoUrl = url,
            durationSeconds = confirmed.durationSeconds,
            minWatchSeconds = confirmed.minWatchSeconds,
            minWatchAdjustedFrom = confirmed.minWatchAdjustedFrom,
            replaced = confirmed.replaced,
            filesDeleted = deleted
          )

  private def reopened(upload: WaitingUpload): ZIO[AssetStore, String, ResumeUploadResponse] =
    signed(upload.key).map(url =>
      ResumeUploadResponse(
        videoId = upload.video,
        title = upload.title.getOrElse(upload.video),
        slug = upload.slug.getOrElse("—"),
        uploadUrl = url,
        expiresInSeconds = UploadValidFor.toSeconds
      ))

  def resume: McpToolHandlerR[AssetStore & Queries.UploadUrl] = McpTool("resume_video_upload")
    .description(
      "Open the upload page again for a file that never arrived: a new video's first file, or a replacement started with replace_video_file. Signs a fresh URL; the previous one having expired does not matter. Does not create anything.")
    .requireScopes(Auth.ContentWrite)
    .reads((in: ResumeUploadRequest) => ZIO.succeed(UploadUrlQuery(in.videoId)))(
      (_, upload: WaitingUpload) => reopened(upload))
    .withUi(UploadUi.AppUri)

  def replace: McpToolHandlerR[
    AssetStore & Queries.UploadUrl & OperatorContext & VideoFlows.StartFileReplacement] = McpTool(
    "replace_video_file")
    .description(
      "Give a video a new file — a lighter encoding, a corrected cut — keeping its id, survey and answers. Opens the upload page for a new file; the video keeps playing its current one, and stays in or out of rotation as it is, until the page confirms the new one by itself. Guests already playing the old file finish it; its object is deleted once the last of them does. Abandoned, nothing changes. Calling it again reopens the same new file. A pending video, still waiting for its first file, is resume_video_upload's.")
    .requireScopes(Auth.ContentWrite)
    .runs((in: ReplaceFileRequest) =>
      for
        id  <- videoIdOf(in.videoId)
        now <- Clock.instant
      yield StartFileReplacementCommand(id, FileId.generate, now)):
      // Read back for the title and the location the page shows.
      (in, _: AwaitedFile) => awaited(in.videoId).flatMap(reopened)
    .withUi(UploadUi.AppUri)

  val update: McpToolHandlerR[OperatorContext & VideoFlows.UpdateVideo] =
    McpTool("update_video")
      .description(
        "Correct a video after it exists: its title, blurb, minWatchSeconds, priority, noRepeatSeconds or the client's campaign id. Omitted fields are left alone. The file is replaced with replace_video_file. Cannot move the video to another location — the location is in the file's key and the survey hangs off it, so that means a new video.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: UpdateVideoRequest) =>
        for
          id  <- videoIdOf(in.videoId)
          now <- Clock.instant
        yield UpdateVideoCommand(
          id,
          in.title.map(LocalizedText.toCore),
          in.description.map(LocalizedText.toCore),
          in.minWatchSeconds,
          in.priority,
          in.noRepeatSeconds,
          in.productCampaignId,
          now
        )): (in, corrected: VideoCorrection) =>
        val written =
          List(
            in.title.map(_ => "title"),
            in.description.map(_ => "description"),
            in.minWatchSeconds.map(_ => "minWatchSeconds"),
            in.priority.map(_ => "priority"),
            in.noRepeatSeconds.map(_ => "noRepeatSeconds"),
            in.productCampaignId.map(_ => "productCampaignId")
          ).flatten
        videoIdOf(in.videoId).map(id =>
          UpdateVideoResponse(id.asString, written, corrected.minWatchAdjustedFrom))

  val setActive
      : McpToolHandlerR[OperatorContext & VideoFlows.RetireVideo & VideoFlows.ReinstateVideo] =
    McpTool("set_video_active")
      .description(
        "Retire a video from rotation, or bring it back. The file and the answers to its survey are kept either way — this is the only removal there is. Refuses a video still waiting for its file: that one becomes active through confirm_video_upload, which is what checks the file is actually there.")
      .requireScopes(Auth.ContentWrite)
      .runsEither((in: SetVideoActiveRequest) =>
        for
          id  <- videoIdOf(in.videoId)
          now <- Clock.instant
        yield
          if in.active then
            Right(ReinstateVideoCommand(id, now))
          else
            Left(RetireVideoCommand(id, now))): (in, rotation: VideosInRotation) =>
        videoIdOf(in.videoId).map(id =>
          SetVideoActiveResponse(
            id.asString,
            if in.active then
              VideoStatus.Active
            else
              VideoStatus.Retired
            ,
            rotation.atLocation))

  val all: List[McpToolHandlerR[
    QuillSqlite & AssetStore & OperatorContext & VideoFlows.All & Queries.VideosOf &
      Queries.PendingUploads & Queries.UploadUrl]] = List(
    create,
    createPromo,
    resume,
    replace,
    confirm,
    list,
    listPending,
    update,
    setActive)
end VideoTools
