package whitelabel.captal.infra.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.{Event, Phase}
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

// TODO: Implement mechanism to detect when user's AP session expires.
// When a user completes identification and later returns (session still valid in our DB),
// we need to know if their AP session has expired to restart the flow.
// Options to investigate:
// - RADIUS Accounting: AP sends Accounting-Stop when session ends
// - UniFi Controller API: Poll /api/s/{site}/stat/sta for active clients
// - Webhooks: Configure controller to notify on session expiration
// - CoA (Change of Authorization): RADIUS server receives disconnect messages
// See: https://ubntwiki.com/products/software/unifi-controller/api

object SessionPhaseHandler:
  def apply(ctx: SessionContext, nextPhaseAfterIdentificationQuestion: Phase): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val hasIdentificationAnswer = events.exists(isIdentificationAnswer)
        if hasIdentificationAnswer then
          for
            sessionData <- ctx.getOrFail
            _ <- run(
              SessionService.updatePhaseQuery(
                lift(sessionData.sessionId),
                lift(nextPhaseAfterIdentificationQuestion))).orDie
          yield ()
        else
          ZIO.unit

  private def isIdentificationAnswer(event: Event): Boolean =
    event match
      case Event.Survey(SurveyEvent.QuestionAnswered(_, questionEvent)) =>
        questionEvent match
          case _: QuestionEvent.EmailQuestionAnswered     => true
          case _: QuestionEvent.ProfilingQuestionAnswered => true
          case _: QuestionEvent.LocationQuestionAnswered  => true
          case _                                          => false
      case _ =>
        false
end SessionPhaseHandler
