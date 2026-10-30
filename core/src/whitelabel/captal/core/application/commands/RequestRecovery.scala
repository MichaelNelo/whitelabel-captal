package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{RecoveryRepository, SessionData}
import whitelabel.captal.core.recovery
import whitelabel.captal.core.recovery.{
  Activity,
  ClientReport,
  ErrorId,
  Evidence,
  Origin,
  Progress,
  Verdict,
  WaitPolicy
}

/** What the session is leaving through. */
enum RecoveryCause:
  /** An error the server caught and handed the session the id of. */
  case Server(errorId: ErrorId)

  /** An error the page says it caught. */
  case Client(report: ClientReport)

final case class RequestRecoveryCommand(cause: RecoveryCause, occurredAt: Instant)

enum RecoveryOutcome:
  /** The session may leave through `errorId` from `availableAt`; `recurrent` when other devices
    * reported the same error, which is what the waiting page tells the guest.
    */
  case Offered(errorId: ErrorId, availableAt: Instant, recurrent: Boolean)

  /** It failed in granting access itself, where nothing on its side can help. `errorId` is the code
    * staff can look it up by.
    */
  case Unrecoverable(errorId: ErrorId)

/** Offers a session a way out of an error, so a guest is not left without internet because
  * something broke.
  *
  * A server error opens at once: the server saw it. A client error waits — long enough that
  * inventing one is slower than going through the flow, short for whoever has shown they would
  * have: what they did on this device, how far this session got, and what the server can tell of
  * the report (see [[WaitPolicy]] and [[Verdict]]).
  */
object RequestRecoveryHandler:
  def withSession[F[_]: Monad](
      repo: RecoveryRepository[F],
      policy: WaitPolicy,
      session: SessionData): Handler.Aux[F, RequestRecoveryCommand, RecoveryOutcome] =
    new Handler[F, RequestRecoveryCommand]:
      type Result = RecoveryOutcome

      def handle(cmd: RequestRecoveryCommand) =
        cmd.cause match
          case RecoveryCause.Server(errorId) =>
            server(errorId, cmd.occurredAt)
          case RecoveryCause.Client(report) =>
            client(report, cmd.occurredAt)

      private def server(errorId: ErrorId, at: Instant): F[Op[RecoveryOutcome]] = repo
        .find(errorId)
        .map:
          case Some(record)
              if record.origin == Origin.Server && record.sessionId.contains(session.sessionId) =>
            if record.redeemedAt.isDefined then
              refuse(recovery.Error.AlreadyRedeemed(errorId))
            else if Progress.isFinal(session.phase) then
              CoreOp.pure(RecoveryOutcome.Unrecoverable(errorId))
            else
              CoreOp.emit[Event, Error, RecoveryOutcome](
                Event.Recovery(recovery.Event.RecoveryOffered(errorId, session.sessionId, at, at)),
                RecoveryOutcome.Offered(errorId, at, recurrent = false))
          case _ =>
            refuse(recovery.Error.UnknownError(errorId))

      private def client(report: ClientReport, at: Instant): F[Op[RecoveryOutcome]] =
        val errorId = ErrorId.generate
        for
          published   <- repo.publishedBundle()
          fingerprint <- report.signature(published).traverse(repo.fingerprintOf)
          others      <-
            fingerprint.fold(Monad[F].pure(0))(
              repo.devicesReporting(_, session.deviceId, at.minus(policy.recurrenceWindow)))
          verdict  = Verdict.of(report, published, others)
          recorded = Event.Recovery(
            recovery
              .Event
              .ErrorRecorded(
                errorId,
                session.sessionId,
                session.deviceId,
                report,
                fingerprint,
                verdict,
                at))
          outcome <-
            if Progress.isFinal(session.phase) then
              Monad[F].pure(
                CoreOp.emit[Event, Error, RecoveryOutcome](
                  recorded,
                  RecoveryOutcome.Unrecoverable(errorId)))
            else
              for
                activity <-
                  session
                    .userId
                    .fold(Monad[F].pure(Activity.Empty))(repo.activity(_, session.deviceId))
                recent <- repo.recoveriesSince(session.deviceId, at.minus(policy.recoveryWindow))
                wait = policy.waitFor(
                  Evidence(activity, Progress.of(session.phase), recent, verdict))
                availableAt = at.plus(wait)
              yield CoreOp.emitAll[Event, Error, RecoveryOutcome](
                List(
                  recorded,
                  Event.Recovery(
                    recovery.Event.RecoveryOffered(errorId, session.sessionId, availableAt, at))),
                RecoveryOutcome.Offered(errorId, availableAt, verdict == Verdict.Plausible(true))
              )
        yield outcome
        end for
      end client

  private[commands] def refuse[A](error: recovery.Error): Op[A] = CoreOp.fail[Event, Error, A](
    Error.Recovery(NonEmptyChain.one(error)))
end RequestRecoveryHandler
