package whitelabel.captal.mcp
import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.{survey, video}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{AdvertiserRow, AdvertiserVideoRow, LocationRow, SurveyRow}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import ToolSchema.{withDiscriminatorInArray, withUi}

/** Advertiser videos, created together with the survey they are followed by.
  *
  * The two are inseparable in the flow: `nextAdQueryAuthenticated` only serves a video whose survey
  * still has an unanswered question, so a video without one is never shown. `captal video add`
  * wrote the survey with the literal text "TODO" and left a bold warning in the skill telling the
  * operator to edit it before pushing — a state that reaches production. Requiring the questions
  * here makes it unreachable.
  */
object VideoTools:
  private val UploadValidFor: Duration = 30.minutes

  final case class CreateVideoIn(
      slug: String,
      advertiserId: String,
      @description("Shown above the player, one entry per locale.")
      title: List[TextIn],
      @description("Optional blurb under the title.")
      description: Option[List[TextIn]],
      @description("The file name to store it under. Keep the extension; it decides the MIME type.")
      fileName: String,
      durationSeconds: Int,
      @description(
        "How long the guest must watch before the survey unlocks. Defaults to the full duration.")
      minWatchSeconds: Option[Int],
      @description(
        "Weights this video against the advertiser's others. Defaults to 10; zero means never drawn.")
      priority: Option[Int],
      @description(
        "Asked after the video. At least one — a video whose survey has nothing left to ask is never served.")
      questions: List[VideoQuestionIn])

  final case class VideoQuestionIn(
      text: List[TextIn],
      question: QuestionSpec,
      @description("Whether an answer is mandatory. Defaults to true.")
      required: Option[Boolean],
      @description("Points awarded for answering. Defaults to 10.")
      points: Option[Int])

  final case class CreateVideoOut(
      videoId: String,
      surveyId: String,
      questionIds: List[String],
      @description("PUT the file here. Send the same Content-Type; the signature covers it.")
      uploadUrl: String,
      contentType: String,
      expiresInSeconds: Long,
      @description(
        "The upload page for this video, when the host renders MCP Apps. Otherwise PUT to uploadUrl directly.")
      uploadPageUri: String,
      @description(
        "The video stays inactive and out of rotation until confirm_video_upload finds the file.")
      active: Boolean)

  final case class ConfirmUploadIn(videoId: String)
  final case class ConfirmUploadOut(videoId: String, active: Boolean, videoUrl: String)

  given Schema[VideoQuestionIn] = DeriveSchema.gen
  given Schema[CreateVideoIn] = DeriveSchema.gen
  given Schema[CreateVideoOut] = DeriveSchema.gen
  given Schema[ConfirmUploadIn] = DeriveSchema.gen
  given Schema[ConfirmUploadOut] = DeriveSchema.gen

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

  val create: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("create_advertiser_video")
    .description(
      "Register an advertiser video together with the survey shown after it, and hand back a URL to upload the file to. The video stays out of rotation until confirm_video_upload finds the file, so an abandoned upload costs nothing. Read skill://add-video/SKILL.md first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateVideoIn) =>
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
                    isActive = 0,
                    priority = in.priority.getOrElse(10),
                    createdAt = now,
                    updatedAt = now
                  )))).orDie
          _ <- Writes.expectOne(affected, s"Creating video '${in.fileName}'")
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
                spec = q.question,
                descriptions = Nil,
                placeholders = Nil,
                required = q.required.getOrElse(true),
                points = q.points.getOrElse(10),
                hierarchyLevel = None,
                displayOrder = index + 1,
                now = now
              )
        yield CreateVideoOut(
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
    .withUi(s"${UploadUi.Uri}/{videoId}")
    // The nested `questions[].question` carries the same `oneOf` as create_question and loses the
    // discriminator the same way. Patched here too, or the seven variants arrive indistinguishable.
    .withDiscriminatorInArray("questions", "question", "questionType", QuestionSpec.caseIds)

  val confirm: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("confirm_video_upload")
    .description(
      "Check the file arrived and put the video into rotation. Refuses when the object is not there, which is what an abandoned upload looks like.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: ConfirmUploadIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          row <- run(videoByIdQuery(lift(in.videoId)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No video with id '${in.videoId}'.")
          store <- ZIO.service[AssetStore]
          key = store.keyOf(row.videoUrl)
          present <- store.exists(key)
          _       <-
            ZIO.when(!present)(
              ZIO.fail(
                s"Nothing was uploaded to $key. The presigned URL may have expired — create the video again to get a fresh one."))
          now = java.time.Instant.now.toString
          affected <-
            run(videoByIdQuery(lift(in.videoId)).update(_.isActive -> 1, _.updatedAt -> lift(now)))
              .orDie
          _ <- Writes.expectOne(affected, s"Activating video '${in.videoId}'")
        yield ConfirmUploadOut(in.videoId, active = true, videoUrl = row.videoUrl)

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(create, confirm)
end VideoTools
