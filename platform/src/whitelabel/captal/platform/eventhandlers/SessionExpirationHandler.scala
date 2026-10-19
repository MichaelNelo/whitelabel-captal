package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.{Event, Phase}
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*

/** Resets the session when access expires.
  *
  * On [[whitelabel.captal.core.user.Event.AccessExpired]] it runs the same
  * `resetForExpirationQuery` as the `/api/status` safety net: a single UPDATE setting `phase =
  * Welcome` and clearing `userId`, `currentSurveyId`, `currentQuestionId`, `currentVideoId`,
  * `currentAdvertiserId` and `accessExpiresAt`. `SessionPhaseHandler` therefore needs no case for
  * this event.
  *
  * The `users` row and the `captal_user` cookie survive, so the next visit skips the questions
  * already answered.
  */
object SessionExpirationHandler:
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*

        val hasExpired = events.exists:
          case Event.User(_: UserEvent.AccessExpired) =>
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
