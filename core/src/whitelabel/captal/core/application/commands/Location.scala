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

/** A new location, `Created`, with the default settings, under a slug no other location has. */
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
              Location(cmd.id, cmd.slug, name, Settings.Default, State.Created))
end CreateLocationHandler

/** A change to some of a location's settings; `None` leaves one as it is, and `Some(None)` clears
  * the duration or the redirect URL.
  */
final case class SettingsChange(
    durationMinutes: Option[Option[Int]] = None,
    redirectUrl: Option[Option[String]] = None,
    unifiEnabled: Option[Boolean] = None,
    fingerprintRequired: Option[Boolean] = None)

final case class ChangeSettingsCommand(slug: Slug, change: SettingsChange, occurredAt: Instant)

/** The location's settings, changed as asked in whatever state it is: they are read when a guest
  * needs them, so they apply with no deploy. Answers them as they now stand.
  */
object ChangeSettingsHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F]): Handler.Aux[F, ChangeSettingsCommand, Settings] =
    new Handler[F, ChangeSettingsCommand]:
      type Result = Settings

      def handle(cmd: ChangeSettingsCommand) =
        Decided.located(locations, cmd.slug): found =>
          val change = cmd.change
          val redirectUrl = change.redirectUrl.map(_.map(_.trim))
          val problems =
            List(
              change.durationMinutes.flatten.filter(_ < 1).map(LocationError.InvalidDuration(_)),
              redirectUrl
                .flatten
                .filterNot(url => url.startsWith("https://") || url.startsWith("http://"))
                .map(LocationError.InvalidRedirectUrl(_))
            ).flatten
          val settings = Settings(
            durationMinutes = change.durationMinutes.getOrElse(found.settings.durationMinutes),
            redirectUrl = redirectUrl.getOrElse(found.settings.redirectUrl),
            unifiEnabled = change.unifiEnabled.getOrElse(found.settings.unifiEnabled),
            fingerprintRequired = change
              .fingerprintRequired
              .getOrElse(found.settings.fingerprintRequired)
          )
          Monad[F].pure(
            Decided(
              NonEmptyChain
                .fromSeq(problems)
                .toLeft(
                  (LocationEvent.SettingsChanged(found.id, settings, cmd.occurredAt), settings))))
end ChangeSettingsHandler

/** Registers the access point `mac` at the location, or changes the controller of the one it has:
  * what the UniFi form saves. `token` may be left out for an access point that has one stored,
  * which it keeps.
  */
final case class RegisterAccessPointCommand(
    slug: Slug,
    mac: Mac,
    host: String,
    port: Option[Int],
    siteId: String,
    token: Option[Secret],
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
                _          <- Decided.unless(
                  holders.find(_ != found.slug).map(LocationError.AccessPointHeld(cmd.mac, _)))
              yield
                val point = AccessPoint(cmd.mac, controller)
                existing match
                  case Some(_) =>
                    (
                      LocationEvent.AccessPointUpdated(found.id, cmd.mac, point, cmd.occurredAt),
                      point)
                  case None =>
                    (LocationEvent.AccessPointRegistered(found.id, point, cmd.occurredAt), point)
end RegisterAccessPointHandler

/** Changes the access point `mac`'s MAC, for one that was replaced, or its controller's host, port
  * or site; each `None` keeps what it has, and its token is kept.
  */
final case class UpdateAccessPointCommand(
    slug: Slug,
    mac: Mac,
    newMac: Option[Mac],
    host: Option[String],
    port: Option[Int],
    siteId: Option[String],
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
            yield
              val updated = AccessPoint(moved.getOrElse(cmd.mac), controller)
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
