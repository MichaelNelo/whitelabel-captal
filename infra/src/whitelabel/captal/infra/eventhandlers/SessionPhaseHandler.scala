package whitelabel.captal.infra.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.{Event, Phase}
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.infra.{DbEventHandler, QuillSchema, QuillSqlite, SessionContext, SessionService}
import whitelabel.captal.infra.QuillSchema.given
import zio.*

object SessionPhaseHandler:
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val phases = events.flatMap(extractPhase)
        if phases.nonEmpty then
          for
            sessionData <- ctx.getOrFail
            sessionId = sessionData.sessionId.asString
            _ <-
              ZIO.foreachDiscard(phases): phase =>
                run(
                  SessionService.updatePhaseQuery(
                    lift(sessionId),
                    lift(SessionService.phaseToString(phase)))).orDie
          yield ()
        else
          ZIO.unit

  private def extractPhase(event: Event): Option[Phase] =
    event match
      case Event.Survey(SurveyEvent.QuestionAnswered(_, questionEvent)) =>
        questionEvent match
          case _: QuestionEvent.EmailQuestionAnswered =>
            Some(Phase.AdvertiserVideo)
          case _: QuestionEvent.ProfilingQuestionAnswered =>
            Some(Phase.AdvertiserVideo)
          case _: QuestionEvent.LocationQuestionAnswered =>
            Some(Phase.AdvertiserVideo)
          case _ =>
            None
      case _ =>
        None
end SessionPhaseHandler
