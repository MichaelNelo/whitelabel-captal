package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{RecoveryRepository, SessionData}
import whitelabel.captal.core.recovery
import whitelabel.captal.core.recovery.{ErrorId, ErrorRecord, Verdict, WaitPolicy}

final case class RedeemRecoveryCommand(errorId: ErrorId, occurredAt: Instant)

/** Leaves through an error offered earlier, once its wait is over: the session moves to
  * `Recovered`, where `finish` grants access with or without a user. The wait is the server's, not
  * the page's clock, and as it stands now ([[OpenOffer]]), shortened if the error became recurrent.
  */
object RedeemRecoveryHandler:
  def withSession[F[_]: Monad](
      repo: RecoveryRepository[F],
      policy: WaitPolicy,
      session: SessionData): Handler.Aux[F, RedeemRecoveryCommand, Unit] =
    new Handler[F, RedeemRecoveryCommand]:
      type Result = Unit

      def handle(cmd: RedeemRecoveryCommand) =
        OpenOffer.find(repo, session, cmd.errorId)(
          OpenOffer
            .current(repo, policy, session, _, cmd.occurredAt)
            .map: (availableAt, _, shortened) =>
              if cmd.occurredAt.isBefore(availableAt) then
                RequestRecoveryHandler.refuse(recovery.Error.NotYet(availableAt))
              else
                CoreOp.emitAll[Event, Error, Unit](
                  shortened :+
                    Event.Recovery(
                      recovery
                        .Event
                        .RecoveryRedeemed(cmd.errorId, session.sessionId, cmd.occurredAt)),
                  ()))
end RedeemRecoveryHandler

/** An offered way out, as it stands now. */
private[commands] object OpenOffer:
  /** The session's own error, offered and not yet taken, or the refusal saying why not. */
  def find[F[_]: Monad, A](repo: RecoveryRepository[F], session: SessionData, errorId: ErrorId)(
      offered: ErrorRecord => F[Op[A]]): F[Op[A]] =
    Monad[F].flatMap(repo.find(errorId)):
      case Some(record) if record.sessionId.contains(session.sessionId) =>
        (record.availableAt, record.redeemedAt) match
          case (_, Some(_)) =>
            Monad[F].pure(RequestRecoveryHandler.refuse(recovery.Error.AlreadyRedeemed(errorId)))
          case (None, None) =>
            Monad[F].pure(RequestRecoveryHandler.refuse(recovery.Error.UnknownError(errorId)))
          case (Some(_), None) =>
            offered(record)
      case _ =>
        Monad[F].pure(RequestRecoveryHandler.refuse(recovery.Error.UnknownError(errorId)))

  /** When the way out opens, whether the error is recurrent, and the event shortening it if it
    * became recurrent since it was offered: another device reported it, so its wait is
    * [[WaitPolicy.recurring]] of what it was, never longer.
    */
  def current[F[_]: Monad](
      repo: RecoveryRepository[F],
      policy: WaitPolicy,
      session: SessionData,
      record: ErrorRecord,
      at: Instant): F[(Instant, Boolean, List[Event])] =
    val availableAt = record.availableAt.getOrElse(record.recordedAt)
    (record.verdict, record.fingerprint) match
      case (Some(Verdict.Plausible(false)), Some(fingerprint)) =>
        repo
          .devicesReporting(
            fingerprint,
            session.deviceId,
            record.recordedAt.minus(policy.recurrenceWindow))
          .map: others =>
            if others >= Verdict.RecurrenceThreshold then
              val shortened = policy.recurring(record.recordedAt, availableAt)
              val opens =
                if shortened.isBefore(availableAt) then
                  shortened
                else
                  availableAt
              (
                opens,
                true,
                List(
                  Event.Recovery(
                    recovery
                      .Event
                      .RecoveryShortened(record.errorId, session.sessionId, opens, at))))
            else
              (availableAt, false, Nil)
      case (Some(Verdict.Plausible(true)), _) =>
        Monad[F].pure((availableAt, true, Nil))
      case _ =>
        Monad[F].pure((availableAt, false, Nil))
    end match
  end current
end OpenOffer
