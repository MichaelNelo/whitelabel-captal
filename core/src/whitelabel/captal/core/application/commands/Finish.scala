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

/** Retry pattern (gratis):
  *   - `session.phase` sólo pasa Ready → Authorized cuando `UnifiAuthorizationHandler` confirma con
  *     el Controller.
  *   - Si UniFi falla / no está configurado, `session.phase` queda en Ready.
  *   - Una nueva llamada a `/api/finish` vuelve a encontrar el user (mismo `findReadyUser`),
  *     re-corre `finish`, re-emite `UserFinishedProcess` y el handler post-commit reintenta.
  *
  * Una vez que UniFi succeed → phase=Authorized → `findReadyUser` devuelve None → fail con
  * `UserNotIdentified` (que el cliente interpreta como "ya estás autorizado").
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
