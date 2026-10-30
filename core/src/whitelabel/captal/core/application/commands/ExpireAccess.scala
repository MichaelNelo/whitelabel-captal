package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import whitelabel.captal.core
import whitelabel.captal.core.access
import whitelabel.captal.core.application.{Error, Event, Phase}
import whitelabel.captal.core.infrastructure.SessionData

final case class ExpireAccessCommand(occurredAt: Instant)

/** Closes the cycle opened by [[FinishCommand]]: the granted access reached its `accessExpiresAt`.
  * Executed by the expiration scheduler, not by a request; the handlers reacting to `AccessExpired`
  * reset the session (transactional) and revoke on the UCG (post-commit).
  *
  * The access is the session's, so is the check: authorized, and past its own `accessExpiresAt`,
  * whether or not it has a user. Anything else is a stale job — the session was reset by
  * `/api/status`, or authorized again since — and a silent no-op, not a failure.
  */
object ExpireAccessHandler:
  def withSession[F[_]: Monad](session: SessionData): Handler.Aux[F, ExpireAccessCommand, Unit] =
    new Handler[F, ExpireAccessCommand]:
      type Result = Unit

      def handle(cmd: ExpireAccessCommand) = Monad[F].pure:
        val due =
          session.phase == Phase.Authorized &&
            session.accessExpiresAt.exists(!_.isAfter(cmd.occurredAt))
        if due then
          core
            .Op
            .emit[Event, Error](
              Event.Access(
                access.Event.AccessExpired(session.sessionId, session.userId, cmd.occurredAt)))
        else
          core.Op.pure[Event, Error, Unit](())
end ExpireAccessHandler
