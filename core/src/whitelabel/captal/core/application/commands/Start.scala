package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.convertEvent
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event, NextStep, Phase}
import whitelabel.captal.core.device.ops.acceptTerms
import whitelabel.captal.core.infrastructure.{DeviceRepository, SessionData, TermsRepository}

/** What leaving the welcome leads to: the identification questions, which hand the guest on
  * themselves when none is left to ask.
  */
private val AfterWelcome = NextStep(Phase.IdentificationQuestion)

/** @param anonymous
  *   whether the request carried neither the session nor the user cookie: a visitor nothing is
  *   known about, asked to accept the terms whatever its device accepted.
  */
final case class ResolveWelcomeCommand(anonymous: Boolean, occurredAt: Instant)

/** Which welcome the session's device is shown: `Welcome` once it accepted the terms of service in
  * force, `WelcomeNeedsToS` until then. Emits nothing.
  */
object ResolveWelcomeHandler:
  def withSession[F[_]: Monad](
      devices: DeviceRepository[F],
      session: SessionData): Handler.Aux[F, ResolveWelcomeCommand, NextStep] =
    new Handler[F, ResolveWelcomeCommand]:
      type Result = NextStep

      def handle(cmd: ResolveWelcomeCommand) = devices
        .findAccepted()
        .map: accepted =>
          val phase =
            if !cmd.anonymous && accepted.isDefined then
              Phase.Welcome
            else
              Phase.WelcomeNeedsToS
          core.Op.pure[Event, Error, NextStep](NextStep(phase))
end ResolveWelcomeHandler

final case class StartCommand(occurredAt: Instant)

/** Leaves the welcome, for a device that accepted the terms of service in force. One that has not
  * fails with `TermsNotAccepted` and stays where it is: the welcome asks it to accept them, which
  * is [[AcceptTermsHandler]].
  */
object StartHandler:
  def withSession[F[_]: Monad](
      devices: DeviceRepository[F],
      session: SessionData): Handler.Aux[F, StartCommand, NextStep] =
    new Handler[F, StartCommand]:
      type Result = NextStep

      def handle(cmd: StartCommand) = devices
        .findAccepted()
        .map:
          case Some(_) =>
            core.Op.pure[Event, Error, NextStep](AfterWelcome)
          case None =>
            core.Op.fail[Event, Error, NextStep](Error.TermsNotAccepted)
end StartHandler

final case class AcceptTermsCommand(occurredAt: Instant)

/** The device accepts the terms of service in force, and leaves the welcome. The version is the
  * server's: the one in force when the acceptance arrives, never one the page names. A device that
  * already accepted it leaves the welcome all the same, accepting nothing again.
  */
object AcceptTermsHandler:
  def withSession[F[_]: Monad](
      terms: TermsRepository[F],
      devices: DeviceRepository[F],
      session: SessionData): Handler.Aux[F, AcceptTermsCommand, NextStep] =
    new Handler[F, AcceptTermsCommand]:
      type Result = NextStep

      def handle(cmd: AcceptTermsCommand) =
        for
          current <- terms.current
          pending <- devices.findNotAccepted()
        yield (current, pending) match
          case (None, _) =>
            core.Op.fail[Event, Error, NextStep](Error.NoTermsOfService)
          case (Some(version), Some(device)) =>
            device.acceptTerms[Error](version, cmd.occurredAt).convertEvent.map(_ => AfterWelcome)
          case (Some(_), None) =>
            core.Op.pure[Event, Error, NextStep](AfterWelcome)
end AcceptTermsHandler
