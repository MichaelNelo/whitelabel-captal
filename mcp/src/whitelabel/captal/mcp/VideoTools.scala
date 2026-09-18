package whitelabel.captal.mcp
import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.{survey, video}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  SurveyRow,
  VideoStatus
}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.withUi

/** Advertiser videos, created together with the survey they are followed by.
  *
  * The two are inseparable in the flow: `nextAdQueryAuthenticated` only serves a video whose survey
  * still has an unanswered question, so a video without one is never shown. `captal video add`
  * wrote the survey with the literal text "TODO" and left a bold warning in the skill telling the
  * operator to edit it before pushing — a state that reaches production. Requiring the questions
  * here makes it unreachable.
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
      @description("The file name to store it under. Keep the extension; it decides the MIME type.")
      fileName: String,
      @description("The video's full length. Drives the default for minWatchSeconds.")
      durationSeconds: Int,
      @description(
        "How long the guest must watch before the survey unlocks. Defaults to the full duration.")
      minWatchSeconds: Option[Int],
      @description(
        "Weights this video against the advertiser's others. Defaults to 10; zero means never drawn.")
      priority: Option[Int],
      @description(
        "Asked after the video. At least one — a video whose survey has nothing left to ask is never served.")
      questions: List[VideoQuestion])

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
      @description("PUT the file here. Send the same Content-Type; the signature covers it.")
      uploadUrl: String,
      @description(
        "Send exactly this on the PUT. The signature covers the header, so a different value is rejected as a signature mismatch rather than a bad type.")
      contentType: String,
      @description(
        "How long the signed URL stays valid. After that, call create_advertiser_video again for a fresh one rather than retrying the PUT.")
      expiresInSeconds: Long,
      @description(
        "The upload page for this video, when the host renders MCP Apps. Otherwise PUT to uploadUrl directly.")
      uploadPageUri: String,
      @description(
        "The video stays inactive and out of rotation until confirm_video_upload finds the file.")
      active: Boolean)

  final case class ResumeUploadRequest(
      @description(
        "The video to upload. Call list_surveys or read captal://videos/pending for what is waiting.")
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
      @description("Send exactly this on the PUT; the signature covers the header.")
      contentType: String,
      @description("How long this signature lasts.")
      expiresInSeconds: Long)

  final case class ConfirmUploadRequest(
      @description("The video whose upload to check, from create_advertiser_video.")
      videoId: String)
  final case class ConfirmUploadResponse(
      @description("The video confirmed.")
      videoId: String,
      @description(
        "True once the object was found and the video entered rotation. The tool refuses rather than returning false, so this reports success.")
      active: Boolean,
      @description("Where the portal will fetch it from.")
      videoUrl: String)

  given Schema[VideoQuestion] = DeriveSchema.gen
  given Schema[CreateVideoRequest] = DeriveSchema.gen
  given Schema[CreateVideoResponse] = DeriveSchema.gen
  given Schema[ResumeUploadRequest] = DeriveSchema.gen
  given Schema[ResumeUploadResponse] = DeriveSchema.gen
  given Schema[ConfirmUploadRequest] = DeriveSchema.gen
  given Schema[ConfirmUploadResponse] = DeriveSchema.gen

  private inline def locationBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  private inline def advertiserByIdQuery = quote: (idParam: String) =>
    query[AdvertiserRow].filter(_.id == idParam)

  private inline def videoByIdQuery = quote: (idParam: String) =>
    query[AdvertiserVideoRow].filter(v => sql"${v.id}".as[String] == idParam)

  /** Derived rather than taken from the caller so two advertisers uploading `promo.mp4` cannot
    * overwrite each other — which is what `captal video add` does today, using the raw file name.
    */
  private def storageKey(slug: String, videoId: String, fileName: String): String =
    val extension = fileName.reverse.takeWhile(_ != '.').reverse
    s"$slug/videos/$videoId.$extension"

  private def contentTypeFor(fileName: String): Either[String, String] =
    fileName.toLowerCase.reverse.takeWhile(_ != '.').reverse match
      case "mp4" =>
        Right("video/mp4")
      case "webm" =>
        Right("video/webm")
      case other =>
        Left(s"Unsupported video format '.$other'. The portal plays mp4 and webm.")

  /** Takes the origin rather than reading it, so the CSP the tool advertises and the URL the page
    * is handed come from the same `AssetStore` instead of two readings of the environment.
    */
  def create(uploadOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = createTool(
    uploadOrigin)

  private def createTool(uploadOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "create_advertiser_video")
    .description(
      "Register an advertiser video together with the survey shown after it, and hand back a URL to upload the file to. The video stays out of rotation until confirm_video_upload finds the file, so an abandoned upload costs nothing. Read skill://add-video/SKILL.md first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateVideoRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          location <- run(locationBySlugQuery(lift(in.slug)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No location with slug '${in.slug}'.")
          advertiser <- run(advertiserByIdQuery(lift(in.advertiserId)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No advertiser with id '${in.advertiserId}'. Call list_advertisers.")
          _ <-
            ZIO.when(advertiser.isActive != 1)(
              ZIO.fail(
                s"Advertiser '${advertiser
                    .name}' is retired, so its videos would never be drawn. Reactivate it first."))
          _ <-
            ZIO.when(in.title.isEmpty)(ZIO.fail("The video needs a title in at least one locale."))
          _ <-
            ZIO.when(in.questions.isEmpty)(
              ZIO.fail(
                "A video needs at least one question. The rotation only serves a video whose survey still has something to ask, so one without any is never shown."))
          _ <-
            ZIO.when(in.durationSeconds <= 0)(
              ZIO.fail(s"Duration must be positive, got ${in.durationSeconds}."))
          minWatch = in.minWatchSeconds.getOrElse(in.durationSeconds)
          _ <-
            ZIO.when(minWatch <= 0 || minWatch > in.durationSeconds)(
              ZIO.fail(
                s"minWatchSeconds must be between 1 and the duration (${in
                    .durationSeconds}), got $minWatch."))
          contentType <- ZIO.fromEither(contentTypeFor(in.fileName))
          videoId = video.Id.generate
          key     = storageKey(in.slug, videoId.asString, in.fileName)
          store <- ZIO.service[AssetStore]
          url   <- store.presignPut(key, contentType, UploadValidFor)
          now = java.time.Instant.now.toString
          // Inactive until the file is there. The row has to exist first — the survey references
          // the video — so this is what keeps a half-finished upload out of the rotation.
          affected <-
            run(
              query[AdvertiserVideoRow].insertValue(
                lift(
                  AdvertiserVideoRow(
                    id = videoId,
                    advertiserId = Some(in.advertiserId),
                    videoType = "publicidad",
                    videoUrl = store.publicUrl(key),
                    durationSeconds = in.durationSeconds,
                    minWatchSeconds = minWatch,
                    showCountdown = 1,
                    noRepeatSeconds = None,
                    locationId = Some(location.id),
                    status = VideoStatus.Pending,
                    priority = in.priority.getOrElse(10),
                    createdAt = now,
                    updatedAt = now
                  )))).orDie
          _ <- Writes.expectOne(affected, s"Creating video '${in.fileName}'")
          // The portal reads these back keyed on the video id, and the description on the id with
          // a `_desc` suffix — see `VideoRepositoryQuill.fetchLocalizedTexts`. Until this was
          // written the tool validated that a title was supplied and then dropped it, so every
          // video created here played untitled.
          _ <- SurveyTools.writeTexts(videoId.asString, in.title, now)
          _ <- SurveyTools.writeTexts(
            s"${videoId.asString}_desc",
            in.description.getOrElse(Nil),
            now)
          surveyId = survey.Id.generate
          surveyWritten <-
            run(
              query[SurveyRow].insertValue(
                lift(
                  SurveyRow(
                    surveyId,
                    "advertiser",
                    Some(in.advertiserId),
                    Some(videoId.asString),
                    Some(location.id),
                    1,
                    now,
                    Some(s"${advertiser.name} — ${in.fileName}"))))).orDie
          _           <- Writes.expectOne(surveyWritten, "Creating the video's survey")
          questionIds <-
            ZIO.foreach(in.questions.zipWithIndex): (q, index) =>
              SurveyTools.writeQuestion(
                surveyId = surveyId,
                surveyCategory = "advertiser",
                text = q.text,
                spec = q.answer,
                descriptions = Nil,
                placeholders = Nil,
                required = q.required.getOrElse(true),
                points = q.points.getOrElse(10),
                hierarchyLevel = None,
                displayOrder = index + 1,
                now = now
              )
        yield CreateVideoResponse(
          videoId = videoId.asString,
          surveyId = surveyId.asString,
          questionIds = questionIds.map(_.questionId),
          uploadUrl = url,
          contentType = contentType,
          expiresInSeconds = UploadValidFor.toSeconds,
          uploadPageUri = s"${UploadUi.Uri}/${videoId.asString}",
          active = false
        )
        end for
    // Points a host that renders MCP Apps at the upload page. The concrete URI is per video and
    // comes back in uploadPageUri; this is the template the host resolves against.
    // The static page, not a per-video template. `_meta.ui.resourceUri` is declared before any call
    // has produced a video, and `CallToolResult` carries no `_meta` to correct it afterwards — a
    // template here is read literally and resolves to nothing, which is what "problem displaying
    // content" was. The page lists whatever is waiting, including what this call just created.
    .withUi(UploadUi.Uri, connectDomains = List(uploadOrigin))

  val confirm: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("confirm_video_upload")
    .description(
      "Check the file arrived and put the video into rotation. Refuses when the object is not there, which is what an abandoned upload looks like.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: ConfirmUploadRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          row <- run(videoByIdQuery(lift(in.videoId)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No video with id '${in.videoId}'.")
          _ <- ZIO
            .fail(
              s"Video '${in.videoId}' is ${row.status}, not pending; there is nothing to confirm.")
            .when(row.status != VideoStatus.Pending)
          store <- ZIO.service[AssetStore]
          key = store.keyOf(row.videoUrl)
          present <- store.exists(key)
          _       <-
            ZIO.when(!present)(
              ZIO.fail(
                s"Nothing was uploaded to $key. The presigned URL may have expired — create the video again to get a fresh one."))
          now = java.time.Instant.now.toString
          affected <-
            run(
              videoByIdQuery(lift(in.videoId)).update(
                _.status    -> lift(VideoStatus.Active),
                _.updatedAt -> lift(now))).orDie
          _ <- Writes.expectOne(affected, s"Activating video '${in.videoId}'")
        yield ConfirmUploadResponse(in.videoId, active = true, videoUrl = row.videoUrl)
        end for

  /** Opens the upload page for a video that already exists.
    *
    * A tool rather than a resource because a View is instantiated only by calling a tool whose
    * definition carries `_meta.ui.resourceUri` — the extension has no path for a host to render a
    * `ui://` resource a user opened on their own. So "let me upload that one" has to be a call, and
    * the video it names travels in the result, which the host forwards to the page.
    *
    * Without it the only way back to an abandoned upload would be create_advertiser_video, which
    * would make a second video, a second survey and a second set of questions.
    */
  def resume(uploadOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "resume_video_upload")
    .description(
      "Open the upload page for a video created earlier whose file never arrived. Signs a fresh URL; the previous one having expired does not matter. Does not create anything.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: ResumeUploadRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          rows  <- run(videoByIdQuery(lift(in.videoId))).orDie
          video <- ZIO.succeed(rows.headOption).someOrFail(s"No video with id '${in.videoId}'.")
          // Only a pending video has a file to upload. A retired one already has its own, and
          // handing out a signed URL for it would let this path put it back into rotation — which
          // is what collapsing 'pending' and 'retired' into one flag made possible.
          _ <- ZIO
            .fail(
              s"Video '${in.videoId}' is ${video
                  .status}, not pending. A retired video keeps its file; create_advertiser_video makes a new one.")
            .when(video.status != VideoStatus.Pending)
          locations <- run(query[LocationRow]).orDie
          titles    <- run(query[LocalizedTextRow].filter(_.entityId == lift(in.videoId))).orDie
          store     <- ZIO.service[AssetStore]
          key = store.keyOf(video.videoUrl)
          // From the stored key, not from a caller-supplied name: the object already exists.
          contentType = contentTypeFor(key).getOrElse("video/mp4")
          url <- store.presignPut(key, contentType, UploadValidFor)
        yield ResumeUploadResponse(
          videoId = in.videoId,
          title = titles.map(_.value).minOption.getOrElse(in.videoId),
          slug = video
            .locationId
            .flatMap(id => locations.find(_.id == id))
            .map(_.slug)
            .getOrElse("—"),
          uploadUrl = url,
          contentType = contentType,
          expiresInSeconds = UploadValidFor.toSeconds
        )
        end for
    .withUi(UploadUi.Uri, connectDomains = List(uploadOrigin))

  def all(uploadOrigin: String): List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(
    create(uploadOrigin),
    resume(uploadOrigin),
    confirm)
end VideoTools
