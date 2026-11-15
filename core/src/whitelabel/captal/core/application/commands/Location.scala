package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{AccessPointRepository, LocationRepository}
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
  Error as LocationError,
  Event as LocationEvent,
  Location,
  LocationId,
  Secret,
  Settings,
  SettingsChange,
  Slug,
  State
}

/** The location's administrative commands: what an operator does to a location that is not an
  * operation on its infrastructure. Each decides on what its repositories read, and answers the
  * event its change is, or every reason it is refused.
  */
private object Decided:
  type Outcome[A] = Either[NonEmptyChain[LocationError], (LocationEvent, A)]

  def apply[A](outcome: Outcome[A]): Op[A] =
    outcome match
      case Left(errors) =>
        CoreOp.fail[Event, Error, A](Error.Location(errors))
      case Right((event, value)) =>
        CoreOp.emit[Event, Error, A](Event.Location(event), value)

  def refused[A](error: LocationError): Either[NonEmptyChain[LocationError], A] = Left(
    NonEmptyChain.one(error))

  /** Refused with `error` when there is one. */
  def unless(error: Option[LocationError]): Either[NonEmptyChain[LocationError], Unit] = error
    .toLeft(())
    .left
    .map(NonEmptyChain.one)

  /** The location with `slug`, or refused when there is none. */
  def located[F[_]: Monad, A](locations: LocationRepository[F], slug: Slug)(
      decide: Location[State] => F[Op[A]]): F[Op[A]] = locations
    .find(slug)
    .flatMap:
      case Some(found) =>
        decide(found)
      case None =>
        Monad[F].pure(apply(refused(LocationError.NotFound(slug))))
end Decided

final case class CreateLocationCommand(
    id: LocationId,
    slug: Slug,
    name: String,
    occurredAt: Instant)

/** A new location, `Created`, under a slug no other location has, requiring a fingerprint. */
object CreateLocationHandler:
  def apply[F[_]: Monad](locations: LocationRepository[F])
      : Handler.Aux[F, CreateLocationCommand, Location[State.Created.type]] =
    new Handler[F, CreateLocationCommand]:
      type Result = Location[State.Created.type]

      def handle(cmd: CreateLocationCommand) = locations
        .find(cmd.slug)
        .map: taken =>
          val name = cmd.name.trim
          Decided:
            for
              _ <- Decided.unless(taken.map(other => LocationError.SlugTaken(cmd.slug, other.name)))
              _ <- Decided.unless(Option.when(name.isEmpty)(LocationError.BlankName))
            yield (
              LocationEvent.LocationCreated(cmd.id, cmd.slug, name, cmd.occurredAt),
              Location(cmd.id, cmd.slug, name, fingerprintRequired = true, State.Created))
end CreateLocationHandler

final case class RequireFingerprintCommand(slug: Slug, required: Boolean, occurredAt: Instant)

/** Whether a redirect into the location's portal has to carry the link shortener's fingerprint, in
  * whatever state the location is: it is read when a guest arrives, so it applies with no deploy.
  */
object RequireFingerprintHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F]): Handler.Aux[F, RequireFingerprintCommand, Unit] =
    new Handler[F, RequireFingerprintCommand]:
      type Result = Unit

      def handle(cmd: RequireFingerprintCommand) =
        Decided.located(locations, cmd.slug): found =>
          Monad[F].pure(
            Decided(
              Right((
                LocationEvent.FingerprintRequirementChanged(found.id, cmd.required, cmd.occurredAt),
                ()))))
end RequireFingerprintHandler

/** Registers the access point `mac` at the location, or changes the one it has: its controller and,
  * as `settings` asks, what the portal does for the guests who join through it — what the UniFi
  * form saves. `token` may be left out for an access point that has one stored, which it keeps; a
  * new one starts from [[Settings.Default]].
  */
final case class RegisterAccessPointCommand(
    slug: Slug,
    mac: Mac,
    host: String,
    port: Option[Int],
    siteId: String,
    token: Option[Secret],
    settings: SettingsChange,
    occurredAt: Instant)

/** Refused while another deployed location takes its guests through the access point, whatever
  * state this one is in: the operator pauses that one first.
  */
object RegisterAccessPointHandler:
  def apply[F[_]: Monad](locations: LocationRepository[F], accessPoints: AccessPointRepository[F])
      : Handler.Aux[F, RegisterAccessPointCommand, AccessPoint] =
    new Handler[F, RegisterAccessPointCommand]:
      type Result = AccessPoint

      def handle(cmd: RegisterAccessPointCommand) =
        Decided.located(locations, cmd.slug): found =>
          for
            points  <- accessPoints.of(found.id)
            holders <- accessPoints.deployedHolders(cmd.mac)
          yield
            val existing = points.find(_.mac == cmd.mac)
            Decided:
              for
                token <- cmd
                  .token
                  .orElse(existing.map(_.controller.token))
                  .toRight(NonEmptyChain.one(LocationError.TokenRequired(cmd.mac)))
                controller <- Controller.of(cmd.host, cmd.port, cmd.siteId, token)
                settings   <- cmd.settings.applyTo(existing.fold(Settings.Default)(_.settings))
                _          <- Decided.unless(
                  holders.find(_ != found.slug).map(LocationError.AccessPointHeld(cmd.mac, _)))
              yield
                val point = AccessPoint(cmd.mac, controller, settings)
                existing match
                  case Some(_) =>
                    (
                      LocationEvent.AccessPointUpdated(found.id, cmd.mac, point, cmd.occurredAt),
                      point)
                  case None =>
                    (LocationEvent.AccessPointRegistered(found.id, point, cmd.occurredAt), point)
end RegisterAccessPointHandler

/** Changes the access point `mac`: its MAC, for one that was replaced, its controller's host, port
  * or site, and its settings as `settings` asks. Each `None` keeps what it has, and its token is
  * kept.
  */
final case class UpdateAccessPointCommand(
    slug: Slug,
    mac: Mac,
    newMac: Option[Mac],
    host: Option[String],
    port: Option[Int],
    siteId: Option[String],
    settings: SettingsChange,
    occurredAt: Instant)

/** A new MAC is refused when the location already has it, or another deployed location takes its
  * guests through it.
  */
object UpdateAccessPointHandler:
  def apply[F[_]: Monad](locations: LocationRepository[F], accessPoints: AccessPointRepository[F])
      : Handler.Aux[F, UpdateAccessPointCommand, AccessPoint] =
    new Handler[F, UpdateAccessPointCommand]:
      type Result = AccessPoint

      def handle(cmd: UpdateAccessPointCommand) =
        Decided.located(locations, cmd.slug): found =>
          val moved = cmd.newMac.filter(_ != cmd.mac)
          for
            points  <- accessPoints.of(found.id)
            holders <- moved.fold(Monad[F].pure(List.empty[Slug]))(accessPoints.deployedHolders)
          yield Decided:
            for
              point <- points
                .find(_.mac == cmd.mac)
                .toRight(NonEmptyChain.one(LocationError.NoSuchAccessPoint(cmd.mac)))
              _ <- Decided.unless(
                moved
                  .filter(mac => points.exists(_.mac == mac))
                  .map(LocationError.AccessPointExists(_)))
              _ <- Decided.unless(
                moved.flatMap(mac =>
                  holders.find(_ != found.slug).map(LocationError.AccessPointHeld(mac, _))))
              controller <- Controller.of(
                cmd.host.getOrElse(point.controller.host),
                cmd.port.orElse(point.controller.port),
                cmd.siteId.getOrElse(point.controller.siteId),
                point.controller.token
              )
              settings <- cmd.settings.applyTo(point.settings)
            yield
              val updated = AccessPoint(moved.getOrElse(cmd.mac), controller, settings)
              (
                LocationEvent.AccessPointUpdated(found.id, cmd.mac, updated, cmd.occurredAt),
                updated)
          end for
end UpdateAccessPointHandler

final case class RemoveAccessPointCommand(slug: Slug, mac: Mac, occurredAt: Instant)

/** Takes an access point off the location; a guest who arrives through it afterwards is turned
  * away. Refused for a deployed location's last one, which would turn every guest away: the
  * operator pauses it first.
  */
object RemoveAccessPointHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F],
      accessPoints: AccessPointRepository[F]): Handler.Aux[F, RemoveAccessPointCommand, Unit] =
    new Handler[F, RemoveAccessPointCommand]:
      type Result = Unit

      def handle(cmd: RemoveAccessPointCommand) =
        Decided.located(locations, cmd.slug): found =>
          accessPoints
            .of(found.id)
            .map: points =>
              Decided:
                for
                  _ <- Decided.unless(
                    Option.unless(points.exists(_.mac == cmd.mac))(
                      LocationError.NoSuchAccessPoint(cmd.mac)))
                  _ <- Decided.unless(
                    found.state match
                      case State.Deployed(_, deployedThrough)
                          if deployedThrough.forall(_.mac == cmd.mac) =>
                        Some(LocationError.LastAccessPoint(cmd.mac))
                      case _ =>
                        None)
                yield (LocationEvent.AccessPointRemoved(found.id, cmd.mac, cmd.occurredAt), ())
end RemoveAccessPointHandler
