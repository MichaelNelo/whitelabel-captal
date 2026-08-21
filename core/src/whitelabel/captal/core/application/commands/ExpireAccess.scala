package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.user.ops.expire

final case class ExpireAccessCommand(occurredAt: Instant)

/** Closes the cycle opened by [[FinishCommand]]: the granted access reached its `accessExpiresAt`.
  *
  * Executed by the expiration scheduler, not by a request. The handlers reacting to `AccessExpired`
  * take care of resetting the session (transactional) and revoking on the UCG (post-commit).
  *
  * Deliberate difference from [[FinishHandler]]: on `None` it does **not** fail.
  * `findAuthorizedUser` returns `None` when the session is no longer authorized — typically because
  * the user redid the flow and got re-authorized with a fresh deadline, leaving the old job stale.
  * That is expected, not an error, so the command is a silent no-op. This is what lets the
  * scheduler avoid cancelling or indexing jobs.
  */
object ExpireAccessHandler:
  def apply[F[_]: Monad](userRepo: UserRepository[F]): Handler.Aux[F, ExpireAccessCommand, Unit] =
    new Handler[F, ExpireAccessCommand]:
      type Result = Unit

      def handle(cmd: ExpireAccessCommand) = userRepo
        .findAuthorizedUser()
        .map:
          case None =>
            core.Op.pure[Event, Error, Unit](())
          case Some(user) =>
            user.expire(cmd.occurredAt).convertEvent.convertError.void
end ExpireAccessHandler
