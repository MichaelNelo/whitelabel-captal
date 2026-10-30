package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.access
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event, Phase}
import whitelabel.captal.core.infrastructure.{SessionData, UserRepository}
import whitelabel.captal.core.user.ops.finish

final case class FinishCommand(occurredAt: Instant)

/** Grants the session access: `AccessGranted`, which is what the controller authorizes on.
  *
  * From `Ready`, the guest went through the flow: the ready user finishes it as well
  * (`UserFinishedProcess`, what they watched and answered), and with no user it fails with
  * `UserNotIdentified`. From `Recovered`, the guest left through an error, possibly before giving
  * an email, so no user is required.
  *
  * `session.phase` only moves once `UnifiAccessHandler` has confirmation from the controller, so if
  * UniFi fails or is not configured the phase stays where it was and a fresh `/api/finish` retries
  * the whole thing.
  */
object FinishHandler:
  def withSession[F[_]: Monad](
      userRepo: UserRepository[F],
      session: SessionData): Handler.Aux[F, FinishCommand, Unit] =
    new Handler[F, FinishCommand]:
      type Result = Unit

      def handle(cmd: FinishCommand) =
        session.phase match
          case Phase.Recovered =>
            Monad[F].pure(granted(session.userId, cmd.occurredAt))
          case _ =>
            userRepo
              .findReadyUser()
              .map:
                case None =>
                  core.Op.fail[Event, Error, Unit](Error.UserNotIdentified)
                case Some(user) =>
                  user
                    .finish(cmd.occurredAt)
                    .convertEvent
                    .convertError
                    .flatMap(_ => granted(Some(user.id), cmd.occurredAt))

      private def granted(userId: Option[core.user.Id], at: Instant) = core
        .Op
        .emit[Event, Error](Event.Access(access.Event.AccessGranted(session.sessionId, userId, at)))
end FinishHandler
