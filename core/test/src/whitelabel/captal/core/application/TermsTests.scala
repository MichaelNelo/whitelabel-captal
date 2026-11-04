package whitelabel.captal.core.application

import java.time.Instant

import cats.Id
import utest.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.device.ops.acceptTerms
import whitelabel.captal.core.device.{Device, State}
import whitelabel.captal.core.infrastructure.{
  DeviceRepository,
  SessionData,
  TermsRepository,
  TermsVersion
}
import whitelabel.captal.core.{Op, device, user}

/** The welcome's terms of service: a device either accepted the version in force or has not, and
  * only the second can accept it.
  */
object TermsTests extends TestSuite:
  private val DeviceId = user.DeviceId.generate
  private val Now = Instant.parse("2026-11-04T10:00:00Z")
  private val InForce = TermsVersion("terms-2", 2)

  private val session = SessionData(
    user.SessionId.generate,
    DeviceId,
    None,
    "es",
    Phase.WelcomeNeedsToS,
    None,
    None)

  /** A device that accepted the version in force, or one that has not. */
  private def devices(accepted: Boolean): DeviceRepository[Id] =
    new DeviceRepository[Id]:
      def findAccepted() = Option.when(accepted)(
        Device(DeviceId, State.Accepted(InForce.id, Now.minusSeconds(60))))
      def findNotAccepted() = Option.when(!accepted)(Device(DeviceId, State.NotAccepted))

  private def terms(inForce: Option[TermsVersion]): TermsRepository[Id] =
    new TermsRepository[Id]:
      def current: Option[TermsVersion] = inForce

  val tests = Tests:
    test("a device that has not accepted them accepts the version in force"):
      val (events, accepted) = Op
        .run(Device(DeviceId, State.NotAccepted).acceptTerms[Error](InForce, Now))
        .toOption
        .get
      assert(
        accepted.state == State.Accepted(InForce.id, Now),
        events == List(device.Event.TermsAccepted(DeviceId, InForce.id, Now)))

    test("the welcome is WelcomeNeedsToS until the device accepted them, and for a stranger"):
      def phaseOf(accepted: Boolean, anonymous: Boolean) = Op
        .run(
          ResolveWelcomeHandler
            .withSession(devices(accepted), session)
            .handle(ResolveWelcomeCommand(anonymous, Now)))
        .toOption
        .map(_._2.phase)
      assert(
        phaseOf(accepted = true, anonymous = false).contains(Phase.Welcome),
        phaseOf(accepted = false, anonymous = false).contains(Phase.WelcomeNeedsToS),
        phaseOf(accepted = true, anonymous = true).contains(Phase.WelcomeNeedsToS))

    test("starting needs them accepted"):
      def started(accepted: Boolean) = Op.run(
        StartHandler.withSession(devices(accepted), session).handle(StartCommand(Now)))
      assert(
        started(true).toOption.map(_._2).contains(NextStep(Phase.IdentificationQuestion)),
        started(false).left.toOption.exists(_.head == Error.TermsNotAccepted))

    test("accepting records the version in force, once, and leaves the welcome"):
      def accept(current: Option[TermsVersion], accepted: Boolean) = Op.run(
        AcceptTermsHandler
          .withSession(terms(current), devices(accepted), session)
          .handle(AcceptTermsCommand(Now)))
      val first = accept(Some(InForce), accepted = false).toOption.get
      val again = accept(Some(InForce), accepted = true).toOption.get
      assert(
        first._1 == List(Event.Device(device.Event.TermsAccepted(DeviceId, InForce.id, Now))),
        first._2 == NextStep(Phase.IdentificationQuestion),
        again._1.isEmpty,
        again._2 == NextStep(Phase.IdentificationQuestion),
        accept(None, accepted = false).left.toOption.exists(_.head == Error.NoTermsOfService)
      )
end TermsTests
