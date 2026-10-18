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
  * Reacts to [[whitelabel.captal.core.user.Event.AccessExpired]] and runs the very same
  * `resetForExpirationQuery` used by the `/api/status` safety net: a single UPDATE setting `phase =
  * Welcome` and clearing `userId`, `currentSurveyId`, `currentQuestionId`, `currentVideoId`,
  * `currentAdvertiserId` and `accessExpiresAt`.
  *
  * A dedicated handler is preferred over spreading the reset across `SessionPhaseHandler` +
  * `SessionSurveyHandler` + `SessionVideoHandler` + `UserPersistenceHandler`: that would be four
  * files reproducing what is already one atomic, correct statement. Hence `SessionPhaseHandler`
  * needs no case for this event.
  *
  * The `users` row and the `captal_user` cookie survive on purpose, so on the next visit the user
  * skips the questions they already answered.
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
