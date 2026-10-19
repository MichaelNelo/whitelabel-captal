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
  * Executed by the expiration scheduler, not by a request; the handlers reacting to `AccessExpired`
  * reset the session (transactional) and revoke on the UCG (post-commit). Unlike [[FinishHandler]],
  * a `None` from `findAuthorizedUser` is a silent no-op, not a failure: a stale job must not fail.
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
