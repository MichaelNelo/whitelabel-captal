package whitelabel.captal.infra.eventhandlers

import java.time.Instant
import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.{DbEventHandler, QuillSchema, QuillSqlite, SessionContext, SurveyService}
import whitelabel.captal.infra.QuillSchema.given
import zio.*

object SurveyProgressHandler:
  private final case class ProgressUpdate(userId: user.Id, surveyId: survey.Id)

  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val updates = events.flatMap(extractProgressUpdate)
        ZIO.foreachDiscard(updates): update =>
          for
            now         <- ZIO.succeed(Instant.now.toString)
            existingOpt <-
              run(
                SurveyService.findByUserAndSurveyQuery(
                  lift(update.userId.asString),
                  lift(update.surveyId.asString))).map(_.headOption).orDie
            progressId <-
              existingOpt match
                case Some(existing) =>
                  run(SurveyService.updateTimestampQuery(lift(existing.id), lift(now)))
                    .orDie
                    .as(existing.id)
                case None =>
                  val newId = UUID.randomUUID.toString
                  val emptyOpt: Option[String] = None
                  run(
                    SurveyService.insertProgressQuery(
                      lift(newId),
                      lift(update.userId.asString),
                      lift(update.surveyId.asString),
                      lift(emptyOpt),
                      lift(emptyOpt),
                      lift(now),
                      lift(now))).orDie.as(newId)
            isComplete <-
              run(
                SurveyService.isSurveyCompleteQuery(
                  lift(update.userId.asString),
                  lift(update.surveyId.asString))).orDie
            _ <-
              ZIO.when(isComplete)(
                run(SurveyService.markCompleteQuery(lift(progressId), lift(now), lift(now))).orDie)
          yield ()
      end handle

  private def extractProgressUpdate(event: Event): Option[ProgressUpdate] =
    event match
      case Event.Survey(SurveyEvent.QuestionAnswered(surveyId, questionEvent)) =>
        questionEvent match
          case evt: QuestionEvent.EmailQuestionAnswered =>
            Some(ProgressUpdate(evt.userId, surveyId))
          case evt: QuestionEvent.ProfilingQuestionAnswered =>
            Some(ProgressUpdate(evt.userId, surveyId))
          case evt: QuestionEvent.LocationQuestionAnswered =>
            Some(ProgressUpdate(evt.userId, surveyId))
          case evt: QuestionEvent.AdvertiserQuestionAnswered =>
            Some(ProgressUpdate(evt.userId, surveyId))
      case _ =>
        None
end SurveyProgressHandler
