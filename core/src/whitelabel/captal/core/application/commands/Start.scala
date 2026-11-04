package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.application.{Error, Event, NextStep, Phase}
import whitelabel.captal.core.device
import whitelabel.captal.core.infrastructure.{SessionData, TermsRepository, TermsVersion}

/** What leaving the welcome leads to: the identification questions, which hand the guest on
  * themselves when none is left to ask.
  */
private val AfterWelcome = NextStep(Phase.IdentificationQuestion)

final case class StartCommand(occurredAt: Instant)

/** Leaves the welcome, for a device that accepted the terms of service in force. One that accepted
  * none, or an older version, fails with `TermsNotAccepted` and stays where it is: the welcome asks
  * it to accept them, which is [[AcceptTermsHandler]].
  */
object StartHandler:
  def withSession[F[_]: Monad](
      terms: TermsRepository[F],
      session: SessionData): Handler.Aux[F, StartCommand, NextStep] =
    new Handler[F, StartCommand]:
      type Result = NextStep

      def handle(cmd: StartCommand) =
        for
          current  <- terms.current
          accepted <- terms.acceptedBy(session.deviceId)
        yield (current, accepted) match
          case (None, _) =>
            core.Op.fail[Event, Error, NextStep](Error.NoTermsOfService)
          case (Some(TermsVersion(id, _)), Some(acceptedId)) if acceptedId == id =>
            core.Op.pure[Event, Error, NextStep](AfterWelcome)
          case _ =>
            core.Op.fail[Event, Error, NextStep](Error.TermsNotAccepted)
end StartHandler

final case class AcceptTermsCommand(occurredAt: Instant)

/** The device accepts the terms of service in force, and leaves the welcome. The version is the
  * server's: the one in force when the acceptance arrives, never one the page names.
  */
object AcceptTermsHandler:
  def withSession[F[_]: Monad](
      terms: TermsRepository[F],
      session: SessionData): Handler.Aux[F, AcceptTermsCommand, NextStep] =
    new Handler[F, AcceptTermsCommand]:
      type Result = NextStep

      def handle(cmd: AcceptTermsCommand) = terms
        .current
        .map:
          case None =>
            core.Op.fail[Event, Error, NextStep](Error.NoTermsOfService)
          case Some(TermsVersion(id, _)) =>
            core
              .Op
              .emit[Event, Error, NextStep](
                Event.Device(device.Event.TermsAccepted(session.deviceId, id, cmd.occurredAt)),
                AfterWelcome)
end AcceptTermsHandler
