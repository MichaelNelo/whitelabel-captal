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

/** Retries come for free:
  *   - `session.phase` moves Ready → Authorized only once `UnifiAccessHandler` has confirmation
  *     from the Controller.
  *   - If UniFi fails, or is not configured, `session.phase` stays at Ready.
  *   - A fresh call to `/api/finish` finds the user again through the same `findReadyUser`, re-runs
  *     `finish`, re-emits `UserFinishedProcess`, and the post-commit handler tries once more.
  *
  * Once UniFi succeeds, phase=Authorized, `findReadyUser` returns None and this fails with
  * `UserNotIdentified` — which the client reads as "you are already authorized".
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
