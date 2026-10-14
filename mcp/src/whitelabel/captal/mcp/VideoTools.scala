package whitelabel.captal.mcp
import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.video.VideoType
import whitelabel.captal.core.{survey, video}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  SurveyCategory as StoredCategory,
  SurveyRow,
  VideoStatus
}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.{readOnly, withUi}

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
      @description("Zero for a pending video, whose length is read from the file at confirm.")
      durationSeconds: Int,
      @description("When the survey unlocks.")
      minWatchSeconds: Int,
      @description("Weights it against the others. Zero means never drawn.")
      priority: Int)

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
        "The video whose upload to check. Comes from create_advertiser_video, create_promo_video, resume_video_upload or list_pending_uploads.")
      videoId: String,
      @description(
        "The file's length in whole seconds. The upload page reads it from the file itself and sends it; supply it by hand only when the page could not — some WebM files carry no duration in their header. It is not a preference: a wrong value makes the player count down to the wrong place.")
      durationSeconds: Int)

  final case class ConfirmUploadResponse(
      @description("The video confirmed.")
      videoId: String,
      @description(
        "True once the object was found and the video entered rotation. The tool refuses rather than returning false, so this reports success.")
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
      minWatchAdjustedFrom: Option[Int])

  /** What a video can be corrected to after it exists.
    *
    * Not the file and not the location. The file is addressed by a key derived from the video's id
    * and S3 is written by the upload page, so replacing it means a new video; the location is in
    * that key and in the survey hanging off it, so moving one would orphan both.
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
  given Schema[ResumeUploadResponse] = DeriveSchema.gen
  given Schema[ConfirmUploadRequest] = DeriveSchema.gen
  given Schema[ConfirmUploadResponse] = DeriveSchema.gen

  private inline def locationBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  private inline def advertiserByIdQuery = quote: (idParam: String) =>
    query[AdvertiserRow].filter(_.id == idParam)

  private inline def videoByIdQuery = quote: (idParam: String) =>
    query[AdvertiserVideoRow].filter(v => sql"${v.id}".as[String] == idParam)

  private inline def activeVideoCountQuery = quote: (advertiserIdParam: String) =>
    query[AdvertiserVideoRow]
      .filter(v => v.advertiserId.contains(advertiserIdParam) && v.status == VideoStatus.Active)
      .size

  /** How many of one advertiser's videos are in rotation.
    *
    * Here rather than in [[AdvertiserTools]], which is the only caller: Quill derives the row's
    * decoder while expanding the macro — for a count as much as for a select — and this module is
    * the one that already carries the encodings the row's domain-typed columns need.
    */
  private[mcp] def countActiveFor(advertiserId: String): ZIO[QuillSqlite, Nothing, Int] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(activeVideoCountQuery(lift(advertiserId))).orDie.map(_.toInt)

  /** Derived rather than taken from the caller so two advertisers uploading `promo.mp4` cannot
    * overwrite each other — which is what `captal video add` does today, using the raw file name.
    */
  /** No extension. The operator has not chosen a file when the video is created, so a name to take
    * one from does not exist yet — and S3 addresses objects by key while browsers and CDNs decide
    * how to play them from the stored content type, not from the path.
    */
  private def storageKey(slug: String, videoId: String): String = s"$slug/videos/$videoId"

  /** Names the survey after the video, for an operator scanning list_surveys. Prefers Spanish and
    * falls back to whatever locale was given.
    */
  private def titleFor(texts: List[LocalizedText]): String = texts
    .find(_.locale == "es")
    .orElse(texts.headOption)
    .map(_.value)
    .getOrElse("video")

  /** No longer takes the bucket's origin. It used to, to declare the CSP on the tool — which is not
    * where the Apps contract puts it, so the declaration did nothing and the PUT was blocked. The
    * origin now reaches the page with the page, in [[UploadUiSource]].
    */
  def create: McpToolHandlerR[QuillSqlite & AssetStore] = createTool

  private def createTool: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "create_advertiser_video")
    .description(
      "Register an advertiser video together with the survey shown after it, and hand back a URL to upload the file to. Does not ask for the video's length: that is read from the file at confirm_video_upload, so do not ask the operator for it. The video stays out of rotation until confirm_video_upload finds the file, so an abandoned upload costs nothing. Call read_skill('add-video') first.")
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
          // Not checked against the length, because the length is not known yet. All that can be
          // said here is that a non-positive one is meaningless; `confirm_video_upload` lowers it
          // to the duration the file turns out to have.
          _ <-
            ZIO.when(in.minWatchSeconds.exists(_ <= 0))(
              ZIO.fail(
                s"minWatchSeconds must be at least 1, got ${in
                    .minWatchSeconds
                    .getOrElse(0)}. Omit it for the whole video."))
          videoId = video.Id.generate
          key     = storageKey(in.slug, videoId.asString)
          store <- ZIO.service[AssetStore]
          url   <- store.presignPut(key, UploadValidFor)
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
                    videoType = VideoType.Ad,
                    videoUrl = store.publicUrl(key),
                    // Both unknown until the file exists. The row cannot wait for it — the survey
                    // references the video — so they are written as zero and filled by
                    // `confirm_video_upload`, which is also what flips the status. Nothing reads
                    // either field while the video is pending, because nothing serves a pending
                    // video.
                    durationSeconds = 0,
                    minWatchSeconds = in.minWatchSeconds.getOrElse(0),
                    showCountdown = 1,
                    noRepeatSeconds = None,
                    locationId = Some(location.id),
                    status = VideoStatus.Pending,
                    priority = in.priority.getOrElse(10),
                    createdAt = now,
                    updatedAt = now,
                    // Carried through untouched. The column has existed since V15 and nothing ever
                    // wrote it, so every video created by this server until now has none.
                    productCampaignId = in.productCampaignId.map(_.trim).filter(_.nonEmpty)
                  )))).orDie
          _ <- Writes.expectOne(affected, s"Creating video '${videoId.asString}'")
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
                    StoredCategory.Advertiser,
                    Some(in.advertiserId),
                    Some(videoId.asString),
                    Some(location.id),
                    1,
                    now,
                    Some(s"${advertiser.name} — ${titleFor(in.title)}")
                  )))).orDie
          _           <- Writes.expectOne(surveyWritten, "Creating the video's survey")
          questionIds <-
            ZIO.foreach(in.questions.zipWithIndex): (q, index) =>
              SurveyTools.writeQuestion(
                surveyId = surveyId,
                surveyCategory = StoredCategory.Advertiser,
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
          expiresInSeconds = UploadValidFor.toSeconds,
          uploadPageUri = s"${UploadUi.Uri}/${videoId.asString}",
          active = false
        )
        end for
    // The static page, not a per-video template. `_meta.ui.resourceUri` is declared before any call
    // has produced a video, and `CallToolResult` carries no `_meta` to correct it afterwards — a
    // template here is read literally and resolves to nothing. The page learns which video it is
    // from the result of the call that opened it.
    .withUi(UploadUi.AppUri)

  /** The house's own videos, which differ from an advertiser's in three ways.
    *
    * They have no advertiser: `nextAdQueryAuthenticated` joins `advertisers` and filters on active
    * ones, and the promo query does not touch that table at all.
    *
    * Their survey is optional, where an advertiser's is mandatory. Not because a promo cannot have
    * one — it can, and the survey is served and answered by the same path — but because the
    * *selection* differs. The advertiser query requires through an `EXISTS` that a question remain
    * unanswered, so an advertiser video with nothing left to ask is never served at all; a promo is
    * served regardless. That is what makes questions required there and optional here. This comment
    * used to claim promos "have no survey", which is where the belief came from that the read path
    * was right to filter them out.
    *
    * And they are not drawn at random: promos are served in ascending `(priority, createdAt)` with
    * `sessions.last_promo_video_id` as the cursor, so `priority` here is a position in a sequence
    * rather than a weight in a lottery.
    *
    * The second half of the upload is shared whole: the same signed PUT, the same page, and the
    * same `confirm_video_upload` reading the duration out of the file.
    */
  val createPromo: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("create_promo_video")
    .description(
      "Register a video of the venue's own — not an advertiser's — and hand back a URL to upload the file to. No advertiser; a survey only if you pass questions, since a promo is served whether or not it has any left to ask. Does not ask for the video's length, which is read from the file at confirm_video_upload. The video stays out of rotation until that call finds the file.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreatePromoRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          location <- run(locationBySlugQuery(lift(in.slug)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No location with slug '${in.slug}'.")
          _ <-
            ZIO.when(in.title.isEmpty)(ZIO.fail("The video needs a title in at least one locale."))
          _ <-
            ZIO.when(in.minWatchSeconds.exists(_ <= 0))(
              ZIO.fail("minWatchSeconds must be at least 1. Omit it for the whole video."))
          _ <-
            ZIO.when(in.noRepeatSeconds.exists(_ < 0))(
              ZIO.fail("noRepeatSeconds cannot be negative. Omit it for no limit."))
          videoId = video.Id.generate
          key     = storageKey(in.slug, videoId.asString)
          store <- ZIO.service[AssetStore]
          url   <- store.presignPut(key, UploadValidFor)
          now = java.time.Instant.now.toString
          affected <-
            run(
              query[AdvertiserVideoRow].insertValue(
                lift(
                  AdvertiserVideoRow(
                    id = videoId,
                    // The whole difference, in one field. A promo belongs to the venue.
                    advertiserId = None,
                    videoType = VideoType.Promo,
                    videoUrl = store.publicUrl(key),
                    durationSeconds = 0,
                    minWatchSeconds = in.minWatchSeconds.getOrElse(0),
                    showCountdown = 1,
                    noRepeatSeconds = in.noRepeatSeconds,
                    locationId = Some(location.id),
                    status = VideoStatus.Pending,
                    priority = in.priority.getOrElse(10),
                    createdAt = now,
                    updatedAt = now
                  )))).orDie
          _ <- Writes.expectOne(affected, s"Creating promo '${videoId.asString}'")
          _ <- SurveyTools.writeTexts(videoId.asString, in.title, now)
          _ <- SurveyTools.writeTexts(
            s"${videoId.asString}_desc",
            in.description.getOrElse(Nil),
            now)
          questions = in.questions.getOrElse(Nil)
          // Only when there are questions. An empty survey is worse than none: the selection serves
          // the promo either way, and the portal would then ask for a survey whose first question
          // does not exist.
          surveyId <-
            ZIO.when(questions.nonEmpty):
              val surveyId = survey.Id.generate
              for
                written <-
                  run(
                    query[SurveyRow].insertValue(
                      lift(
                        SurveyRow(
                          surveyId,
                          StoredCategory.Advertiser,
                          // No advertiser. The category still reads `advertiser` because that is
                          // what the stored vocabulary calls the survey that follows a video;
                          // `advertiser_id` is what says whose video it was, and a promo's is the
                          // venue's.
                          None,
                          Some(videoId.asString),
                          Some(location.id),
                          1,
                          now,
                          Some(s"${location.name} — ${titleFor(in.title)}")
                        )))).orDie
                _ <- Writes.expectOne(written, "Creating the promo's survey")
              yield surveyId
              end for
          questionIds <-
            ZIO.foreach(surveyId.toList): sid =>
              ZIO.foreach(questions.zipWithIndex): (q, index) =>
                SurveyTools.writeQuestion(
                  surveyId = sid,
                  surveyCategory = StoredCategory.Advertiser,
                  text = q.text,
                  spec = q.answer,
                  descriptions = Nil,
                  placeholders = Nil,
                  required = q.required.getOrElse(true),
                  points = q.points.getOrElse(10),
                  hierarchyLevel = None,
                  // `index + 1`, matching `create_advertiser_video` above. This wrote `index`, so a
                  // promo's questions started at 0 and an advertiser's at 1 — harmless, since
                  // nothing compares the two and `reorder_questions` rewrites to 1-based anyway,
                  // but two paths disagreeing about the same column is how the harmless kind
                  // becomes the other kind.
                  displayOrder = index + 1,
                  now = now
                )
        yield CreatePromoResponse(
          videoId = videoId.asString,
          uploadUrl = url,
          expiresInSeconds = UploadValidFor.toSeconds,
          uploadPageUri = s"${UploadUi.Uri}/${videoId.asString}",
          active = false,
          surveyId = surveyId.map(_.asString),
          questionIds = questionIds.flatten.map(_.questionId)
        )
        end for
    .withUi(UploadUi.AppUri)

  /** What a location actually plays.
    *
    * Nothing answered this before — not a tool and not a resource. `list_pending_uploads` sees only
    * what is waiting for a file and `captal://videos` never existed, so "what does this location
    * show, and in what order" had no answer at all; the rotation was visible only by reading the
    * database.
    */
  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_videos")
    .description(
      "Every video a location plays, with its status, length and rotation weight. Promos and advertiser videos alike. Retired ones are left out unless asked for.")
    .readOnly
    .handle: (in: ListVideosRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          videos      <- run(query[AdvertiserVideoRow]).orDie
          locations   <- run(query[LocationRow]).orDie
          advertisers <- run(query[AdvertiserRow]).orDie
          titles      <- run(query[LocalizedTextRow].filter(_.isActive == 1)).orDie
          slugOf  = locations.map(l => l.id -> l.slug).toMap
          nameOf  = advertisers.map(a => a.id -> a.name).toMap
          titleOf = titles.groupBy(_.entityId).view.mapValues(_.map(_.value).minOption).toMap
          wanted  = in.slug.map(_.trim).filter(_.nonEmpty)
          retired = in.includeRetired.getOrElse(false)
          listed  = videos
            .toList
            .map: row =>
              val id = row.id.toString
              VideoView(
                videoId = id,
                title = titleOf.get(id).flatten.getOrElse(id),
                slug = row.locationId.flatMap(slugOf.get).getOrElse("—"),
                // `advertiser`, not `ad`: the field names whose video it is, and that is the word
                // every other tool in this file uses for the entity. The stored value is `ad`.
                kind =
                  row.videoType match
                    case VideoType.Ad =>
                      "advertiser"
                    case VideoType.Promo =>
                      "promo"
                ,
                advertiser = row.advertiserId.flatMap(nameOf.get),
                status = row.status,
                durationSeconds = row.durationSeconds,
                minWatchSeconds = row.minWatchSeconds,
                priority = row.priority
              )
            .filter(v => wanted.forall(_ == v.slug))
            .filter(v => retired || v.status != VideoStatus.Retired)
            // Active first, because that is what guests see; priority descending within a status,
            // because that is the order the rotation draws in.
            .sortBy: v =>
              val rank =
                if v.status == VideoStatus.Active then
                  0
                else if v.status == VideoStatus.Pending then
                  1
                else
                  2
              (rank, -v.priority, v.title)
        yield ListVideosResponse(listed)
        end for

  /** Every video registered whose file never arrived.
    *
    * Also what `captal://videos/pending` reads, so the resource and the tool cannot disagree about
    * what is waiting.
    */
  private[mcp] def pending(slug: Option[String]): ZIO[QuillSqlite, Nothing, List[PendingUpload]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        videos <- run(query[AdvertiserVideoRow].filter(_.status == lift(VideoStatus.Pending))).orDie
        locations <- run(query[LocationRow]).orDie
        titles    <- run(query[LocalizedTextRow].filter(_.isActive == 1)).orDie
        slugOf  = locations.map(l => l.id -> l.slug).toMap
        titleOf = titles.groupBy(_.entityId).view.mapValues(_.map(_.value).minOption).toMap
        wanted  = slug.map(_.trim).filter(_.nonEmpty)
      yield videos
        .toList
        .map: video =>
          val id = video.id.toString
          PendingUpload(
            videoId = id,
            title = titleOf.get(id).flatten.getOrElse(id),
            slug = video.locationId.flatMap(slugOf.get).getOrElse("—"),
            createdAt = video.createdAt)
        .filter(p => wanted.forall(_ == p.slug))
        // Oldest first: an upload abandoned weeks ago is the one nobody is going to remember on
        // their own, and the one a listing sorted by title would bury.
        .sortBy(_.createdAt)
      end for

  /** The model's way back to an abandoned upload.
    *
    * `captal://videos/pending` answered this first and still does — but a resource is not something
    * every host lets a model read. In Claude Desktop they are offered to the operator as
    * attachments and never to the model, so the only path back to a half-finished upload ran
    * through the operator noticing and pasting one in. Which is to say: there was none.
    */
  val listPending: McpToolHandlerR[QuillSqlite] = McpTool("list_pending_uploads")
    .description(
      "Videos registered but still waiting for their file, oldest first. Each one has a survey and questions already written and is invisible to guests until the file arrives, so finishing one is cheaper than creating another. Pass a videoId to resume_video_upload to open its page.")
    .readOnly
    .handle: (in: PendingUploadsRequest) =>
      pending(in.slug).map(PendingUploadsResponse.apply)

  /** When the survey unlocks, once the video's real length is known.
    *
    * `create_advertiser_video` stores what was asked for, or zero for "the whole video" — it cannot
    * resolve either, because at that point no file exists. Both are settled here.
    *
    * A request longer than the video is lowered rather than refused. By the time this runs the file
    * is already in S3, there is no tool to edit the field, and a survey that unlocks after the
    * video ends never unlocks — so refusing would leave an operator with an upload they cannot
    * complete and a video they cannot fix.
    *
    * @param requested
    *   what create stored: zero meaning the whole video.
    * @param duration
    *   the length read from the file.
    */
  private[mcp] def resolveMinWatch(requested: Int, duration: Int): Int =
    if requested <= 0 then
      duration
    else
      math.min(requested, duration)

  /** The second half of the upload, and where the file stops being a claim.
    *
    * The length used to be an argument to `create_advertiser_video` — supplied before any file
    * existed, by whoever was describing the video rather than by the video. Nothing checked it, and
    * a wrong number is not visible anywhere in the backoffice: it shows up as a countdown that ends
    * early or a survey that unlocks late, to guests, in production.
    *
    * It belongs here because here the file exists. The page reads it from the file it is about to
    * send and passes it along with the confirmation, so the recorded length is measured rather than
    * declared.
    */
  val confirm: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("confirm_video_upload")
    .description(
      "Check the file arrived, record its length, and put the video into rotation. Refuses when the object is not there, which is what an abandoned upload looks like. The upload page calls this itself with the duration it read from the file.")
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
          _ <-
            ZIO.when(in.durationSeconds <= 0)(
              ZIO.fail(
                s"durationSeconds must be at least 1, got ${in
                    .durationSeconds}. The upload page reads it from the file; if it could not, ask the operator and pass it here."))
          store <- ZIO.service[AssetStore]
          key = store.keyOf(row.videoUrl)
          // A HEAD, not a download: the question is whether the object arrived, and a video can be
          // hundreds of megabytes. What it was sent as is not checked here — the page puts the
          // file's own type on the PUT, and re-deciding whether that type is acceptable would be
          // second-guessing a fact with a list.
          present <- store.exists(key)
          _       <-
            ZIO.when(!present)(
              ZIO.fail(
                s"Nothing was uploaded to $key. The signed URL may have lapsed — call resume_video_upload for a fresh one."))
          now       = java.time.Instant.now.toString
          requested = row.minWatchSeconds
          minWatch  = resolveMinWatch(requested, in.durationSeconds)
          affected <-
            run(
              videoByIdQuery(lift(in.videoId)).update(
                _.status          -> lift(VideoStatus.Active),
                _.durationSeconds -> lift(in.durationSeconds),
                _.minWatchSeconds -> lift(minWatch),
                _.updatedAt       -> lift(now))).orDie
          _ <- Writes.expectOne(affected, s"Activating video '${in.videoId}'")
        yield ConfirmUploadResponse(
          videoId = in.videoId,
          active = true,
          videoUrl = row.videoUrl,
          durationSeconds = in.durationSeconds,
          minWatchSeconds = minWatch,
          minWatchAdjustedFrom = Option.when(requested > in.durationSeconds)(requested)
        )
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
  def resume: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("resume_video_upload")
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
          url <- store.presignPut(key, UploadValidFor)
        yield ResumeUploadResponse(
          videoId = in.videoId,
          title = titles.map(_.value).minOption.getOrElse(in.videoId),
          slug = video
            .locationId
            .flatMap(id => locations.find(_.id == id))
            .map(_.slug)
            .getOrElse("—"),
          uploadUrl = url,
          expiresInSeconds = UploadValidFor.toSeconds
        )
        end for
    .withUi(UploadUi.AppUri)

  val update: McpToolHandlerR[QuillSqlite] = McpTool("update_video")
    .description(
      "Correct a video after it exists: its title, blurb, minWatchSeconds, priority, noRepeatSeconds or the client's campaign id. Omitted fields are left alone. Cannot replace the file or move the video to another location — the S3 key is derived from the video's id and the survey hangs off it, so both of those mean a new video.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: UpdateVideoRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          row <- run(videoByIdQuery(lift(in.videoId)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No video with id '${in.videoId}'. Call list_videos to see what exists.")
          _ <-
            ZIO.when(in.minWatchSeconds.exists(_ <= 0))(
              ZIO.fail("minWatchSeconds must be at least 1."))
          _ <- ZIO.when(in.priority.exists(_ < 0))(ZIO.fail("priority must be zero or greater."))
          _ <-
            ZIO.when(in.noRepeatSeconds.exists(_ < 0))(
              ZIO.fail("noRepeatSeconds cannot be negative."))
          now <- Clock.instant.map(_.toString)
          // Re-resolved against the stored duration, which `confirm_video_upload` read off the
          // file. A pending video has duration zero and nothing to clamp against, so the request
          // stands as given and confirm will clamp it later.
          resolved = in
            .minWatchSeconds
            .map: requested =>
              if row.durationSeconds > 0 then
                resolveMinWatch(requested, row.durationSeconds)
              else
                requested
          adjusted = in
            .minWatchSeconds
            .zip(resolved)
            .collect {
              case (requested, applied) if applied != requested =>
                requested
            }
          _ <- ZIO.foreachDiscard(in.title)(SurveyTools.replaceTexts(in.videoId, _, now))
          _ <-
            ZIO.foreachDiscard(in.description)(
              SurveyTools.replaceTexts(s"${in.videoId}_desc", _, now))
          rowChanged =
            in.minWatchSeconds.isDefined || in.priority.isDefined || in.noRepeatSeconds.isDefined ||
              in.productCampaignId.isDefined
          _ <-
            ZIO.when(rowChanged):
              val minWatch = resolved.getOrElse(row.minWatchSeconds)
              val priority = in.priority.getOrElse(row.priority)
              val noRepeat = in.noRepeatSeconds.orElse(row.noRepeatSeconds)
              // An empty string clears it, which is the only way to say "there is no campaign any
              // more" through a field that is already optional for "do not touch".
              val campaign =
                in.productCampaignId
                  .map(_.trim)
                  .fold(row.productCampaignId)(v => Option.when(v.nonEmpty)(v))
              run(
                videoByIdQuery(lift(in.videoId)).update(
                  _.minWatchSeconds   -> lift(minWatch),
                  _.priority          -> lift(priority),
                  _.noRepeatSeconds   -> lift(noRepeat),
                  _.productCampaignId -> lift(campaign),
                  _.updatedAt         -> lift(now)
                )).orDie.flatMap(Writes.expectOne(_, s"Updating video '${in.videoId}'"))
          written =
            List(
              in.title.map(_ => "title"),
              in.description.map(_ => "description"),
              in.minWatchSeconds.map(_ => "minWatchSeconds"),
              in.priority.map(_ => "priority"),
              in.noRepeatSeconds.map(_ => "noRepeatSeconds"),
              in.productCampaignId.map(_ => "productCampaignId")
            ).flatten
        yield UpdateVideoResponse(in.videoId, written, adjusted)
        end for

  /** The off switch V21 made representable and nothing could set.
    *
    * `retired` and `pending` are different states on purpose: `pending` never had a file, `retired`
    * has one and was taken out of rotation deliberately. Before V21 both were `is_active = 0`,
    * which let `confirm_video_upload` find a retired video's file and put it back into rotation
    * through a path meant for uploads, without anyone asking.
    *
    * So this refuses a pending video rather than activating it. There is nothing to serve, and the
    * only thing that may make a video active is `confirm_video_upload`, once the file is there.
    */
  val setActive: McpToolHandlerR[QuillSqlite] = McpTool("set_video_active")
    .description(
      "Retire a video from rotation, or bring it back. The file and the answers to its survey are kept either way — this is the only removal there is. Refuses a video still waiting for its file: that one becomes active through confirm_video_upload, which is what checks the file is actually there.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetVideoActiveRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          row <- run(videoByIdQuery(lift(in.videoId)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No video with id '${in.videoId}'. Call list_videos to see what exists.")
          _ <-
            ZIO.when(row.status == VideoStatus.Pending)(
              ZIO.fail(
                s"Video '${in
                    .videoId}' is still waiting for its file, so there is nothing to put into rotation. Upload it and call confirm_video_upload, or call resume_video_upload for a fresh signed URL."))
          target =
            if in.active then
              VideoStatus.Active
            else
              VideoStatus.Retired
          now <- Clock.instant.map(_.toString)
          _   <- run(
            videoByIdQuery(lift(in.videoId)).update(
              _.status    -> lift(target),
              _.updatedAt -> lift(now)))
            .orDie
            .flatMap(Writes.expectOne(_, s"Setting video '${in.videoId}' active=${in.active}"))
          siblings <- run(query[AdvertiserVideoRow]).orDie
          activeHere = siblings.count(v =>
            v.locationId == row.locationId && (
              if v.id.asString == in.videoId then
                target == VideoStatus.Active
              else
                v.status == VideoStatus.Active
            ))
        yield SetVideoActiveResponse(in.videoId, target, activeHere)
        end for

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(
    create,
    createPromo,
    resume,
    confirm,
    list,
    listPending,
    update,
    setActive)
end VideoTools
