package whitelabel.captal.core.application

import java.time.Instant

import cats.Id
import cats.data.NonEmptyList
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.access.Event as AccessEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.device.{
  Device,
  Event as DeviceEvent,
  Mac,
  Signals,
  State as DeviceState
}
import whitelabel.captal.core.infrastructure.{
  DeviceLookup,
  LocationRepository,
  SessionData,
  SessionRepository,
  UserDirectory
}
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
  Location,
  LocationId,
  Secret,
  Settings,
  Slug,
  State as LocationState
}
import whitelabel.captal.core.operation.RunId
import whitelabel.captal.core.recovery.{ErrorId, Event as RecoveryEvent}
import whitelabel.captal.core.session.Event as SessionEvent
import whitelabel.captal.core.user

object EnterPortalTests extends TestSuite:
  private val Now = Instant.parse("2026-11-15T12:00:00Z")
  private val Venue = LocationId("plaza-id")
  private val Ap = "1c:6a:1b:ee:5c:be"
  private val Phone = "aa:bb:cc:dd:ee:01"
  private val NewSession = user.SessionId.generate
  private val NewDevice = user.DeviceId.generate
  private val NewError = ErrorId.generate
  private val KnownDevice = user.DeviceId.generate
  private val KnownUser = user.Id.generate

  private def deployed(fingerprintRequired: Boolean = true): Location[LocationState.Deployed] =
    Location(
      Venue,
      Slug.unsafe("plaza"),
      "Plaza",
      fingerprintRequired,
      LocationState.Deployed(
        RunId("run-1"),
        NonEmptyList.one(
          AccessPoint(
            Mac.parse(Ap).get,
            Controller("unifi.example", None, "site", Secret.parse("token").get),
            Settings.Default)))
    )

  private def accepted(id: user.DeviceId) = Device[DeviceState](
    id,
    DeviceState.Accepted("terms-1", Now.minusSeconds(60)))

  private def handler(
      location: Option[Location[LocationState.Deployed]] = Some(deployed()),
      session: Option[SessionData] = None,
      device: Option[Device[DeviceState]] = None,
      users: Set[user.Id] = Set.empty) = EnterPortalHandler[Id](
    new LocationRepository[Id]:
      def find(slug: Slug) = None
      def findDeployed(id: LocationId) = location.filter(_.id == id)
    ,
    new SessionRepository[Id]:
      def find(id: user.SessionId) = session.filter(_.sessionId == id)
    ,
    new DeviceLookup[Id]:
      def matching(seen: Signals) = device
      def byId(id: user.DeviceId) = device.filter(_.id == id)
    ,
    new UserDirectory[Id]:
      def exists(id: user.Id) = users.contains(id)
  )

  private def enter(
      clientMac: Option[String] = Some(Phone),
      apMac: Option[String] = Some(Ap),
      fingerprint: Option[String] = Some("fp-1"),
      sessionId: Option[user.SessionId] = None,
      userId: Option[user.Id] = None,
      anonymous: Boolean = true,
      location: Option[LocationId] = Some(Venue)) = EnterPortalCommand(
    location,
    Redirect(clientMac, apMac, "https://after.example", "Guest", fingerprint),
    sessionId,
    userId,
    anonymous,
    "agent",
    "es",
    NewSession,
    NewDevice,
    NewError,
    Now
  )

  private def outcome(op: Op[Event, Error, Entered]) = Op.run(op).left.map(_.toNonEmptyList.toList)

  val tests = Tests:
    test("refuses"):
      test("a location that is not deployed"):
        val result = outcome(handler(location = None).handle(enter()))
        assert(result == Left(List(Error.LocationNotDeployed(Venue))))
      test("a redirect into the location naming no access point"):
        val result = outcome(handler().handle(enter(apMac = None)))
        assert(result == Left(List(Error.InvalidClient(List(RedirectField.AccessPoint)))))
      test("a redirect through an access point that is not the location's"):
        val result = outcome(handler().handle(enter(apMac = Some("aa:aa:aa:aa:aa:aa"))))
        assert(result == Left(List(Error.UnknownAccessPoint(Mac.parse("aa:aa:aa:aa:aa:aa").get))))
      test("a page reached without the redirect, naming what it lacks"):
        val result = outcome(handler().handle(enter(clientMac = None, apMac = None)))
        assert(
          result ==
            Left(
              List(Error.InvalidClient(List(RedirectField.ClientMac, RedirectField.AccessPoint)))))

    test("starts a session"):
      test("on a new device, at the welcome that asks for the terms"):
        val Right((events, entered)) = Op.run(handler().handle(enter())): @unchecked
        assert(
          events.collect { case Event.Device(e: DeviceEvent.DeviceRegistered) =>
            e.deviceId
          } == List(NewDevice),
          entered ==
            Entered(
              NewSession,
              Phase.WelcomeNeedsToS,
              "es",
              Some("https://after.example"),
              None,
              None)
        )
      test("on the known device the signals match, under the known user, at the welcome"):
        val Right((events, entered)) =
          Op.run(
            handler(device = Some(accepted(KnownDevice)), users = Set(KnownUser)).handle(
              enter(userId = Some(KnownUser), anonymous = false))): @unchecked
        val started = events.collect { case Event.Session(e: SessionEvent.SessionStarted) =>
          e
        }
        assert(
          events.exists {
            case Event.Device(e: DeviceEvent.DeviceRecognized) =>
              e.deviceId == KnownDevice
            case _ =>
              false
          },
          started.map(s => (s.deviceId, s.userId, s.phase)) ==
            List((KnownDevice, Some(KnownUser), Phase.Welcome)),
          entered.phase == Phase.Welcome
        )
      test("asking a stranger for the terms whatever its device accepted"):
        val Right((_, entered)) =
          Op.run(
            handler(device = Some(accepted(KnownDevice))).handle(
              enter(anonymous = true))): @unchecked
        assert(entered.phase == Phase.WelcomeNeedsToS)
      test("under no user when the one the cookie names is gone"):
        val Right((events, _)) =
          Op.run(handler().handle(enter(userId = Some(KnownUser)))): @unchecked
        assert(
          events.collect { case Event.Session(e: SessionEvent.SessionStarted) =>
            e.userId
          } == List(None))
      test("with the stand-in and a way out where a required fingerprint is missing"):
        val Right((events, entered)) =
          Op.run(handler().handle(enter(fingerprint = None))): @unchecked
        assert(
          events.collect { case Event.Recovery(e: RecoveryEvent.FingerprintMissing) =>
            (e.errorId, e.location)
          } == List((NewError, Some("plaza"))),
          events.collect { case Event.Session(e: SessionEvent.SessionStarted) =>
            e.fingerprint
          } == List(Signals.NoFingerprint),
          entered.recovery.contains(NewError)
        )
      test("with the stand-in and no way out where the fingerprint is optional"):
        val Right((events, entered)) =
          Op.run(
            handler(location = Some(deployed(fingerprintRequired = false))).handle(
              enter(fingerprint = None))): @unchecked
        assert(
          !events.exists {
            case Event.Recovery(_) =>
              true
            case _ =>
              false
          },
          entered.recovery.isEmpty)
      test("from a cookie naming a session that is gone"):
        val Right((_, entered)) =
          Op.run(handler().handle(enter(sessionId = Some(user.SessionId.generate)))): @unchecked
        assert(entered.sessionId == NewSession)

    test("resumes a session"):
      def session(phase: Phase, expiresAt: Option[Instant] = None) = SessionData(
        sessionId = user.SessionId.generate,
        deviceId = KnownDevice,
        userId = Some(KnownUser),
        locale = "en",
        phase = phase,
        currentQuestion = None,
        currentVideoId = None,
        redirectUrl = "https://after.example",
        accessExpiresAt = expiresAt
      )
      test("where it stands, emitting nothing"):
        val resumed = session(Phase.Ready)
        val Right((events, entered)) =
          Op.run(
            handler(session = Some(resumed)).handle(
              enter(sessionId = Some(resumed.sessionId), anonymous = false))): @unchecked
        assert(
          events.isEmpty,
          entered ==
            Entered(
              resumed.sessionId,
              Phase.Ready,
              "en",
              Some("https://after.example"),
              None,
              None))
      test("reset to the welcome once its access ran out"):
        val resumed = session(Phase.Authorized, Some(Now.minusSeconds(1)))
        val Right((events, entered)) =
          Op.run(
            handler(session = Some(resumed)).handle(
              enter(sessionId = Some(resumed.sessionId), anonymous = false))): @unchecked
        assert(
          events.contains(
            Event.Access(AccessEvent.AccessExpired(resumed.sessionId, resumed.userId, Now))),
          // Its device accepted no terms here, so the welcome asks for them.
          events.contains(
            Event.Session(
              SessionEvent.WelcomeResolved(resumed.sessionId, Phase.WelcomeNeedsToS, Now))),
          entered.redirectUrl.isEmpty,
          entered.accessExpiresAt.isEmpty
        )
      test("still authorized while its access lasts"):
        val resumed = session(Phase.Authorized, Some(Now.plusSeconds(600)))
        val Right((events, entered)) =
          Op.run(
            handler(session = Some(resumed)).handle(
              enter(sessionId = Some(resumed.sessionId), anonymous = false))): @unchecked
        assert(events.isEmpty, entered.accessExpiresAt.contains(Now.plusSeconds(600)))
      test("at the welcome its device's terms decide"):
        val resumed = session(Phase.WelcomeNeedsToS)
        val Right((events, entered)) =
          Op.run(
            handler(session = Some(resumed), device = Some(accepted(KnownDevice))).handle(
              enter(sessionId = Some(resumed.sessionId), anonymous = false))): @unchecked
        assert(
          events ==
            List(
              Event.Session(SessionEvent.WelcomeResolved(resumed.sessionId, Phase.Welcome, Now))),
          entered.phase == Phase.Welcome)
end EnterPortalTests
