package whitelabel.captal.infra.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

object SessionSurveyHandler:
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val assignments = events.flatMap(extractQuestionAssignment)
        if assignments.nonEmpty then
          for
            sessionData <- ctx.getOrFail
            _           <-
              ZIO.foreachDiscard(assignments): question =>
                run(
                  SessionService.updateCurrentSurveyQuery(
                    lift(sessionData.sessionId),
                    lift(question.surveyId),
                    lift(question.questionId))).orDie
          yield ()
        else
          ZIO.unit

  private def extractQuestionAssignment(event: Event): Option[FullyQualifiedQuestionId] =
    event match
      case Event.User(UserEvent.SurveyAssigned(_, nextQuestion, _)) =>
        Some(nextQuestion)
      case Event.User(UserEvent.NewUserArrived(_, Some(nextQuestion), _)) =>
        Some(nextQuestion)
      case _ =>
        None
end SessionSurveyHandler
