package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.{Event, Phase}
import whitelabel.captal.core.access.Event as AccessEvent
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*

/** Resets the session when access expires.
  *
  * On [[whitelabel.captal.core.access.Event.AccessExpired]] it runs the same
  * `resetForExpirationQuery` as the `/api/status` safety net: a single UPDATE setting `phase =
  * Welcome` and clearing `currentSurveyId`, `currentQuestionId`, `currentVideoId`,
  * `currentAdvertiserId` and `accessExpiresAt`. `SessionPhaseHandler` therefore needs no case for
  * this event.
  *
  * The session keeps its `userId`, and the `users` row and the `captal_user` cookie survive, so the
  * next visit skips the questions already answered.
  */
object SessionExpirationHandler:
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*

        val hasExpired = events.exists:
          case Event.Access(_: AccessEvent.AccessExpired) =>
            true
          case _ =>
            false

        ZIO
          .when(hasExpired):
            for
              sessionData <- ctx.getOrFail
              _           <-
                run(
                  SessionService.resetForExpirationQuery(
                    lift(sessionData.sessionId),
                    lift(Phase.Welcome))).orDie
            yield ()
          .unit
      end handle
end SessionExpirationHandler
