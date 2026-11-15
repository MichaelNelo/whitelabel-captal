package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.access.Event as AccessEvent
import whitelabel.captal.core.application.{Error, Event, Phase, RedirectField}
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
import whitelabel.captal.core.location.{Location, LocationId, State as LocationState}
import whitelabel.captal.core.recovery.{ErrorId, Event as RecoveryEvent}
import whitelabel.captal.core.session.Event as SessionEvent
import whitelabel.captal.core.user

/** What a redirect into the portal carries, as it came: the device's MAC and the access point's,
  * which UniFi always sends, and where it sends the guest afterwards; the fingerprint, which the
  * link shortener in front of the portal adds.
  */
final case class Redirect(
    clientMac: Option[String],
    apMac: Option[String],
    redirectUrl: String,
    ssid: String,
    fingerprint: Option[String])

/** Every load of the portal's page: a first arrival, a reload, a return, the way back from the
  * error page.
  *
  * @param location
  *   the location the portal serves; none in development, where nothing of a location is checked
  * @param sessionId
  *   the session the cookie names
  * @param userId
  *   the user the cross-location cookie names
  * @param anonymous
  *   whether the request carried neither cookie: a visitor nothing is known about, asked to accept
  *   the terms whatever its device accepted
  * @param newSession
  *   the ids a session started here takes, made by the caller as the instant is
  */
final case class EnterPortalCommand(
    location: Option[LocationId],
    redirect: Redirect,
    sessionId: Option[user.SessionId],
    userId: Option[user.Id],
    anonymous: Boolean,
    userAgent: String,
    locale: String,
    newSession: user.SessionId,
    newDevice: user.DeviceId,
    newError: ErrorId,
    occurredAt: Instant)

/** Where the guest stands once in: their session, its phase and locale, where UniFi sends them
  * afterwards, until when their access lasts, and the error they leave through when one was
  * recorded as the session started.
  */
final case class Entered(
    sessionId: user.SessionId,
    phase: Phase,
    locale: String,
    redirectUrl: Option[String],
    accessExpiresAt: Option[Instant],
    recovery: Option[ErrorId])

/** Resumes the session the cookie names, or starts one from the redirect, and decides the welcome.
  *
  *   - A location the portal serves has to be deployed, and a redirect into it has to come through
  *     one of its access points: refused otherwise, as expected, with no way out.
  *   - A session is started only from a redirect with the device's MAC: without one there is no
  *     device to grant access to. Its device is the known one the signals match, or a new one.
  *   - A fingerprint the location requires and the redirect lacks is the link shortener failing,
  *     not the guest: the session starts with the stand-in, and the error it is recorded as is the
  *     way out.
  *   - A session whose access ran out is reset to the welcome.
  *   - At the welcome, a guest who is known and whose device accepted the terms in force is
  *     `Welcome`; anyone else, `WelcomeNeedsToS`.
  */
object EnterPortalHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F],
      sessions: SessionRepository[F],
      devices: DeviceLookup[F],
      users: UserDirectory[F]): Handler.Aux[F, EnterPortalCommand, Entered] =
    new Handler[F, EnterPortalCommand]:
      type Result = Entered

      def handle(cmd: EnterPortalCommand) =
        val clientMac = cmd.redirect.clientMac.flatMap(Mac.parse)
        val apMac = cmd.redirect.apMac.flatMap(Mac.parse)
        cmd
          .location
          .traverse(id => locations.findDeployed(id).map(_.toRight(id)))
          .flatMap:
            case Some(Left(id)) =>
              refused(Error.LocationNotDeployed(id))
            case served =>
              val location = served.flatMap(_.toOption)
              throughAccessPoint(location, clientMac, apMac) match
                case Some(error) =>
                  refused(error)
                case None =>
                  cmd
                    .sessionId
                    .flatTraverse(sessions.find)
                    .flatMap:
                      case Some(session) =>
                        resume(cmd, session)
                      case None =>
                        clientMac match
                          case Some(mac) =>
                            start(cmd, location, mac, apMac)
                          case None =>
                            refused(
                              Error.InvalidClient(
                                RedirectField.ClientMac ::
                                  Option.when(apMac.isEmpty)(RedirectField.AccessPoint).toList))
              end match
      end handle

      private def refused(error: Error): F[Op[Entered]] = Monad[F].pure(
        CoreOp.fail[Event, Error, Entered](error))

      /** A redirect into a served location — one with the device's MAC — has to name one of its
        * access points, which UniFi always sends.
        */
      private def throughAccessPoint(
          location: Option[Location[LocationState.Deployed]],
          clientMac: Option[Mac],
          apMac: Option[Mac]): Option[Error] =
        (location, clientMac) match
          case (Some(served), Some(_)) =>
            apMac match
              case None =>
                Some(Error.InvalidClient(List(RedirectField.AccessPoint)))
              case Some(ap) if !served.state.accessPoints.exists(_.mac == ap) =>
                Some(Error.UnknownAccessPoint(ap))
              case Some(_) =>
                None
          case _ =>
            None

      private def welcome(device: Option[Device[DeviceState]], anonymous: Boolean): Phase =
        device.map(_.state) match
          case Some(_: DeviceState.Accepted) if !anonymous =>
            Phase.Welcome
          case _ =>
            Phase.WelcomeNeedsToS

      private def resume(cmd: EnterPortalCommand, session: SessionData): F[Op[Entered]] =
        val expired =
          session.phase == Phase.Authorized &&
            session.accessExpiresAt.exists(_.isBefore(cmd.occurredAt))
        val stored =
          if expired then
            Phase.Welcome
          else
            session.phase
        val reset =
          Option.when(expired)(
            Event.Access(
              AccessEvent.AccessExpired(session.sessionId, session.userId, cmd.occurredAt)))
        val atWelcome = stored == Phase.Welcome || stored == Phase.WelcomeNeedsToS
        val decided =
          if atWelcome then
            devices.byId(session.deviceId).map(device => welcome(device, cmd.anonymous))
          else
            Monad[F].pure(stored)
        decided.map: phase =>
          val resolved =
            Option.when(phase != stored)(
              Event.Session(SessionEvent.WelcomeResolved(session.sessionId, phase, cmd.occurredAt)))
          CoreOp.emitAll[Event, Error, Entered](
            reset.toList ++ resolved.toList,
            Entered(
              session.sessionId,
              phase,
              session.locale,
              Option.unless(expired)(session.redirectUrl).filter(_.nonEmpty),
              Option.unless(expired)(session.accessExpiresAt).flatten,
              recovery = None
            )
          )
      end resume

      private def start(
          cmd: EnterPortalCommand,
          location: Option[Location[LocationState.Deployed]],
          mac: Mac,
          apMac: Option[Mac]): F[Op[Entered]] =
        // Without a location, one is required: nothing says otherwise.
        val required = location.forall(_.fingerprintRequired)
        val sent = cmd
          .redirect
          .fingerprint
          .filter(_.nonEmpty)
          .orElse(Option.unless(required)(Signals.NoFingerprint))
        val seen = Signals(Some(mac), sent.getOrElse(Signals.NoFingerprint))
        for
          known     <- devices.matching(seen)
          knownUser <- cmd.userId.flatTraverse(id => users.exists(id).map(Option.when(_)(id)))
        yield
          val deviceId = known.fold(cmd.newDevice)(_.id)
          val device =
            known.fold(
              DeviceEvent.DeviceRegistered(cmd.newDevice, cmd.userAgent, seen, cmd.occurredAt))(d =>
              DeviceEvent.DeviceRecognized(d.id, cmd.userAgent, seen, cmd.occurredAt))
          val phase = welcome(known, cmd.anonymous)
          val started = SessionEvent.SessionStarted(
            cmd.newSession,
            deviceId,
            knownUser,
            cmd.location.map(_.asString),
            cmd.locale,
            phase,
            mac,
            apMac,
            cmd.redirect.redirectUrl,
            cmd.redirect.ssid,
            seen.fingerprint,
            cmd.occurredAt
          )
          val missing =
            Option.when(sent.isEmpty)(
              RecoveryEvent.FingerprintMissing(
                cmd.newError,
                cmd.newSession,
                deviceId,
                location.map(_.slug.asString),
                cmd.occurredAt))
          CoreOp.emitAll[Event, Error, Entered](
            List(Event.Device(device), Event.Session(started)) ++ missing.map(Event.Recovery(_)),
            Entered(
              cmd.newSession,
              phase,
              cmd.locale,
              Option(cmd.redirect.redirectUrl).filter(_.nonEmpty),
              accessExpiresAt = None,
              recovery = missing.map(_ => cmd.newError))
          )
        end for
      end start
end EnterPortalHandler
