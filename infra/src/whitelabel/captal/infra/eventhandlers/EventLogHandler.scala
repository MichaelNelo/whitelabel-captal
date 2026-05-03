package whitelabel.captal.infra.eventhandlers

import java.time.Instant
import java.util.UUID

import io.circe.Json
import io.circe.syntax.*
import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.core.video.{Event as VideoEvent, VideoType}
import whitelabel.captal.infra.EventLogRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.session.SessionContext
import zio.*

object EventLogHandler:
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        for
          sessionData <- ctx.getOrFail
          now  = Instant.now.toString
          rows = events.flatMap: event =>
            toEventLogRow(
              event,
              sessionData.sessionId.asString,
              sessionData.userId.map(_.asString),
              now)
          _ <-
            if rows.nonEmpty then
              ZIO.foreachDiscard(rows)(row => run(query[EventLogRow].insertValue(lift(row))).orDie)
            else
              ZIO.unit
        yield ()

  private def toEventLogRow(
      event: Event,
      sessionId: String,
      userId: Option[String],
      now: String): Option[EventLogRow] = extractEventInfo(event).map:
    (eventType, eventData, occurredAt) =>
      EventLogRow(
        id = UUID.randomUUID.toString,
        eventType = eventType,
        eventData = eventData.noSpaces,
        sessionId = sessionId,
        userId = userId,
        occurredAt = occurredAt.toString,
        createdAt = now
      )

  private def extractEventInfo(event: Event): Option[(String, Json, Instant)] =
    event match
      case Event.User(e) =>
        extractUserEvent(e)
      case Event.Survey(e) =>
        extractSurveyEvent(e)
      case Event.Video(e) =>
        extractVideoEvent(e)

  private def extractUserEvent(event: UserEvent): Option[(String, Json, Instant)] =
    event match
      case UserEvent.UserCreated(userId, email, occurredAt) =>
        Some(
          (
            "user.created",
            Json.obj(
              "userId"     -> Json.fromString(userId.asString),
              "email"      -> Json.fromString(email.value),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case UserEvent.NewUserArrived(userId, nextQuestion, occurredAt) =>
        Some(
          (
            "user.new_arrived",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "nextQuestion" ->
                nextQuestion
                  .map(q =>
                    Json.obj(
                      "surveyId"   -> Json.fromString(q.surveyId.asString),
                      "questionId" -> Json.fromString(q.questionId.asString)))
                  .getOrElse(Json.Null),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.SurveyAssigned(userId, nextQuestion, occurredAt) =>
        Some(
          (
            "user.survey_assigned",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "nextQuestion" ->
                Json.obj(
                  "surveyId"   -> Json.fromString(nextQuestion.surveyId.asString),
                  "questionId" -> Json.fromString(nextQuestion.questionId.asString)),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.IdentificationCompleted(userId, occurredAt) =>
        Some(
          (
            "user.identification_completed",
            Json.obj(
              "userId"     -> Json.fromString(userId.asString),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case UserEvent.VideoAssigned(userId, videoId, advertiserId, videoType, occurredAt) =>
        Some(
          (
            "user.video_assigned",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "videoId"      -> Json.fromString(videoId.asString),
              "advertiserId" -> advertiserId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "videoType"    -> Json.fromString(VideoType.toDbString(videoType)),
              "occurredAt"   -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.VideoSurveyAssigned(userId, advertiserId, nextQuestion, occurredAt) =>
        Some(
          (
            "user.video_survey_assigned",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "advertiserId" -> Json.fromString(advertiserId.asString),
              "nextQuestion" ->
                Json.obj(
                  "surveyId"   -> Json.fromString(nextQuestion.surveyId.asString),
                  "questionId" -> Json.fromString(nextQuestion.questionId.asString)),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))

  private def extractSurveyEvent(event: SurveyEvent): Option[(String, Json, Instant)] =
    event match
      case SurveyEvent.QuestionAnswered(surveyId, questionEvent) =>
        questionEvent match
          case QuestionEvent.EmailQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
            Some(
              (
                "survey.email_answered",
                Json.obj(
                  "userId"     -> Json.fromString(userId.asString),
                  "surveyId"   -> Json.fromString(surveyId.asString),
                  "questionId" -> Json.fromString(questionId.asString),
                  "answer"     -> answer.value.asJson,
                  "occurredAt" -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))
          case QuestionEvent.ProfilingQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
            Some(
              (
                "survey.profiling_answered",
                Json.obj(
                  "userId"     -> Json.fromString(userId.asString),
                  "surveyId"   -> Json.fromString(surveyId.asString),
                  "questionId" -> Json.fromString(questionId.asString),
                  "answer"     -> answer.value.asJson,
                  "occurredAt" -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))
          case QuestionEvent.LocationQuestionAnswered(
                userId,
                _,
                questionId,
                hierarchyLevel,
                answer,
                occurredAt) =>
            Some(
              (
                "survey.location_answered",
                Json.obj(
                  "userId"         -> Json.fromString(userId.asString),
                  "surveyId"       -> Json.fromString(surveyId.asString),
                  "questionId"     -> Json.fromString(questionId.asString),
                  "hierarchyLevel" -> hierarchyLevel.asJson,
                  "answer"         -> answer.value.asJson,
                  "occurredAt"     -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))
          case QuestionEvent.AdvertiserQuestionAnswered(
                userId,
                _,
                advertiserId,
                questionId,
                answer,
                occurredAt) =>
            Some(
              (
                "survey.advertiser_answered",
                Json.obj(
                  "userId"       -> Json.fromString(userId.asString),
                  "surveyId"     -> Json.fromString(surveyId.asString),
                  "advertiserId" -> Json.fromString(advertiserId.asString),
                  "questionId"   -> Json.fromString(questionId.asString),
                  "answer"       -> answer.value.asJson,
                  "occurredAt"   -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))

  private def extractVideoEvent(event: VideoEvent): Option[(String, Json, Instant)] =
    event match
      case VideoEvent.VideoVisualized(
            sessionId,
            userId,
            videoId,
            advertiserId,
            videoType,
            durationWatched,
            completed,
            occurredAt) =>
        Some(
          (
            "video.visualized",
            Json.obj(
              "sessionId"       -> Json.fromString(sessionId.asString),
              "userId"          -> userId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "videoId"         -> Json.fromString(videoId.asString),
              "advertiserId"    -> advertiserId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "videoType"       -> Json.fromString(VideoType.toDbString(videoType)),
              "durationWatched" -> Json.fromInt(durationWatched),
              "completed"       -> Json.fromBoolean(completed),
              "occurredAt"      -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
end EventLogHandler
