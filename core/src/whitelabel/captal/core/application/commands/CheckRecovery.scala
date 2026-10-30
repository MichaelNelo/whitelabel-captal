package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{RecoveryRepository, SessionData}
import whitelabel.captal.core.recovery.{ErrorId, WaitPolicy}

final case class CheckRecoveryCommand(errorId: ErrorId, occurredAt: Instant)

/** Where a session's way out stands while it waits: asked again and again by the waiting page, so
  * that when another device reports the same error the wait shortens there and then, and the guest
  * is told why. The shortening is recorded, so the redeem finds it too.
  */
object CheckRecoveryHandler:
  def withSession[F[_]: Monad](
      repo: RecoveryRepository[F],
      policy: WaitPolicy,
      session: SessionData): Handler.Aux[F, CheckRecoveryCommand, RecoveryOutcome] =
    new Handler[F, CheckRecoveryCommand]:
      type Result = RecoveryOutcome

      def handle(cmd: CheckRecoveryCommand) =
        OpenOffer.find(repo, session, cmd.errorId)(
          OpenOffer
            .current(repo, policy, session, _, cmd.occurredAt)
            .map: (availableAt, recurrent, shortened) =>
              CoreOp.emitAll[Event, Error, RecoveryOutcome](
                shortened,
                RecoveryOutcome.Offered(cmd.errorId, availableAt, recurrent)))
end CheckRecoveryHandler
