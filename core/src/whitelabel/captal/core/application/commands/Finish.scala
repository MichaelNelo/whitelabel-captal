package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.user.ops.finish

final case class FinishCommand(occurredAt: Instant)

/** Ready → Authorized. `session.phase` only moves once `UnifiAccessHandler` has confirmation from
  * the Controller, so if UniFi fails or is not configured the phase stays at Ready and a fresh
  * `/api/finish` call retries the whole thing. Once it succeeds, `findReadyUser` returns None and
  * this fails with `UserNotIdentified`, which the client reads as "you are already authorized".
  */
object FinishHandler:
  def apply[F[_]: Monad](userRepo: UserRepository[F]): Handler.Aux[F, FinishCommand, Unit] =
    new Handler[F, FinishCommand]:
      type Result = Unit

      def handle(cmd: FinishCommand) = userRepo
        .findReadyUser()
        .map:
          case None =>
            core.Op.fail[Event, Error, Unit](Error.UserNotIdentified)
          case Some(user) =>
            user.finish(cmd.occurredAt).convertEvent.convertError.void
end FinishHandler
