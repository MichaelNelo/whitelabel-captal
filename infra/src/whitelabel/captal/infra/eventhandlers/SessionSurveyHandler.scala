package whitelabel.captal.infra.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.{DbEventHandler, SessionContext, SessionService}
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

object SessionSurveyHandler:
  final private case class SurveyAssignment(surveyId: survey.Id, questionId: survey.question.Id)

  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val assignments = events.flatMap(extractSurveyAssignment)
        if assignments.nonEmpty then
          for
            sessionData <- ctx.getOrFail
            _           <-
              ZIO.foreachDiscard(assignments): assignment =>
                run(
                  SessionService.updateCurrentSurveyQuery(
                    lift(sessionData.sessionId),
                    lift(assignment.surveyId),
                    lift(assignment.questionId))).orDie
          yield ()
        else
          ZIO.unit

  private def extractSurveyAssignment(event: Event): Option[SurveyAssignment] =
    event match
      case Event.User(UserEvent.SurveyAssigned(_, surveyId, questionId, _)) =>
        Some(SurveyAssignment(surveyId, questionId))
      case Event.User(UserEvent.NewUserArrived(surveyId, questionId, _)) =>
        Some(SurveyAssignment(surveyId, questionId))
      case _ =>
        None
end SessionSurveyHandler
