package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{
  AccessPointRepository,
  ControllerRepository,
  LocationRepository
}
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
  ControllerChoice,
  ControllerId,
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

  /** As [[apply]], for a change that is several events, in the order they are written. */
  def all[A](outcome: Either[NonEmptyChain[LocationError], (List[LocationEvent], A)]): Op[A] =
    outcome match
      case Left(errors) =>
        CoreOp.fail[Event, Error, A](Error.Location(errors))
      case Right((events, value)) =>
        CoreOp.emitAll[Event, Error, A](events.map(Event.Location(_)), value)

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

/** Configures the location with the access point `mac`, or changes how it treats the guests who
  * join through one it has — what the UniFi form saves. An access point the system has answers to
  * its own controller; a new one to `controller`, one the system has or a new one. A new
  * configuration starts from [[Settings.Default]], and `settings` changes it as it asks.
  */
final case class RegisterAccessPointCommand(
    slug: Slug,
    mac: Mac,
    controller: Option[ControllerChoice],
    settings: SettingsChange,
    occurredAt: Instant)

/** Any number of locations can be configured with one access point, so registering one another
  * location has is allowed — unless this one is deployed, which takes it at once: then it is
  * refused while another location has taken it ([[LocationError.AccessPointHeld]]). A new
  * controller is refused at an address one is already at, and an access point the system has
  * refuses a controller other than its own.
  */
object RegisterAccessPointHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F],
      accessPoints: AccessPointRepository[F],
      controllers: ControllerRepository[F])
      : Handler.Aux[F, RegisterAccessPointCommand, AccessPoint] =
    new Handler[F, RegisterAccessPointCommand]:
      type Result = AccessPoint

      def handle(cmd: RegisterAccessPointCommand) =
        Decided.located(locations, cmd.slug): found =>
          for
            points <- accessPoints.of(found.id)
            known  <- accessPoints.controllerOf(cmd.mac)
            taken  <- accessPoints.takenBy(cmd.mac)
            chosen <- chosenController(cmd.controller, controllers)
          yield
            val existing = points.find(_.mac == cmd.mac)
            Decided.all:
              for
                (registered, controller) <-
                  (existing.map(_.controller).orElse(known), chosen) match
                    case (Some(own), None) =>
                      Right((None, own))
                    case (Some(own), Some(Right(Chosen.Existing(choice)))) if choice.id == own.id =>
                      Right((None, own))
                    case (Some(own), Some(_)) =>
                      Decided.refused(LocationError.AccessPointAnswersTo(cmd.mac, own.id))
                    case (None, None) =>
                      Decided.refused(LocationError.ControllerRequired(cmd.mac))
                    case (None, Some(Left(errors))) =>
                      Left(errors)
                    case (None, Some(Right(Chosen.Existing(choice)))) =>
                      Right((None, choice))
                    case (None, Some(Right(Chosen.Made(made)))) =>
                      Right((Some(made), made))
                settings <- cmd.settings.applyTo(existing.fold(Settings.Default)(_.settings))
                _        <- Decided.unless(heldElsewhere(found, cmd.mac, taken))
              yield
                val point = AccessPoint(cmd.mac, controller, settings)
                val change =
                  existing match
                    case Some(_) =>
                      LocationEvent.AccessPointUpdated(found.id, cmd.mac, point, cmd.occurredAt)
                    case None =>
                      LocationEvent.AccessPointRegistered(found.id, point, cmd.occurredAt)
                (
                  registered.map(LocationEvent.ControllerRegistered(_, cmd.occurredAt)).toList :+
                    change,
                  point)
              end for
end RegisterAccessPointHandler

/** A controller a command chose: one the system has, or one it makes. */
private enum Chosen:
  case Existing(controller: Controller)
  case Made(controller: Controller)

/** The controller `choice` names, or why there is none: an id the system does not have, a new one
  * whose fields are refused, or a new one at an address a controller is already at — that one is
  * chosen, not made again.
  */
private def chosenController[F[_]: Monad](
    choice: Option[ControllerChoice],
    controllers: ControllerRepository[F]): F[Option[Either[NonEmptyChain[LocationError], Chosen]]] =
  choice match
    case None =>
      Monad[F].pure(None)
    case Some(ControllerChoice.Existing(id)) =>
      controllers
        .find(id)
        .map(found =>
          Some(
            found
              .map(Chosen.Existing(_))
              .toRight(NonEmptyChain.one(LocationError.NoSuchController(id)))))
    case Some(ControllerChoice.New(id, host, port, siteId, token)) =>
      controllers
        .at(host.trim, port, siteId.trim)
        .map: there =>
          Some(
            there.fold(Controller.of(id, host, port, siteId, token).map(Chosen.Made(_)))(existing =>
              Decided.refused(LocationError.ControllerExists(existing))))

/** Refused when the location is deployed — so it takes the access point at once — and another
  * location has taken it.
  */
private def heldElsewhere(
    found: Location[State],
    mac: Mac,
    taken: Option[Slug]): Option[LocationError] =
  found.state match
    case _: State.Deployed =>
      taken.filter(_ != found.slug).map(LocationError.AccessPointHeld(mac, _))
    case _ =>
      None

/** Changes how the location treats the guests of its access point `mac`, as `settings` asks; with
  * `controller`, the controller that access point answers to, for every location configured with
  * it; with `newMac`, the location is configured with the access point of that MAC in its place —
  * the system's, or a new one answering to the same controller. Each `None` keeps what it has.
  */
final case class UpdateAccessPointCommand(
    slug: Slug,
    mac: Mac,
    newMac: Option[Mac],
    controller: Option[ControllerId],
    settings: SettingsChange,
    occurredAt: Instant)

/** A new MAC is refused when the location already has it, or when the location is deployed and
  * another location has taken it.
  */
object UpdateAccessPointHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F],
      accessPoints: AccessPointRepository[F],
      controllers: ControllerRepository[F]): Handler.Aux[F, UpdateAccessPointCommand, AccessPoint] =
    new Handler[F, UpdateAccessPointCommand]:
      type Result = AccessPoint

      def handle(cmd: UpdateAccessPointCommand) =
        Decided.located(locations, cmd.slug): found =>
          val moved = cmd.newMac.filter(_ != cmd.mac)
          for
            points <- accessPoints.of(found.id)
            known  <- moved.fold(Monad[F].pure(Option.empty[Controller]))(accessPoints.controllerOf)
            taken  <- moved.fold(Monad[F].pure(Option.empty[Slug]))(accessPoints.takenBy)
            chosen <-
              cmd
                .controller
                .fold(Monad[F].pure(Option.empty[Option[Controller]]))(id =>
                  controllers.find(id).map(Some(_)))
          yield Decided:
            for
              point <- points
                .find(_.mac == cmd.mac)
                .toRight(NonEmptyChain.one(LocationError.NoSuchAccessPoint(cmd.mac)))
              _ <- Decided.unless(
                moved
                  .filter(mac => points.exists(_.mac == mac))
                  .map(LocationError.AccessPointExists(_)))
              _          <- Decided.unless(moved.flatMap(heldElsewhere(found, _, taken)))
              controller <-
                (cmd.controller, chosen) match
                  case (Some(id), Some(None)) =>
                    Decided.refused(LocationError.NoSuchController(id))
                  case (_, Some(Some(controller))) =>
                    Right(controller)
                  case _ =>
                    Right(known.getOrElse(point.controller))
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

/** Changes the controller `id`, for every access point that answers to it: where it is, and with
  * `token` the token it takes. Each `None` keeps what it has.
  */
final case class UpdateControllerCommand(
    id: ControllerId,
    host: Option[String],
    port: Option[Int],
    siteId: Option[String],
    token: Option[Secret],
    occurredAt: Instant)

/** Refused at an address another controller is already at. */
object UpdateControllerHandler:
  def apply[F[_]: Monad](
      controllers: ControllerRepository[F]): Handler.Aux[F, UpdateControllerCommand, Controller] =
    new Handler[F, UpdateControllerCommand]:
      type Result = Controller

      def handle(cmd: UpdateControllerCommand) = controllers
        .find(cmd.id)
        .flatMap:
          case None =>
            Monad[F].pure(Decided(Decided.refused(LocationError.NoSuchController(cmd.id))))
          case Some(current) =>
            val host = cmd.host.getOrElse(current.host)
            val port = cmd.port.orElse(current.port)
            val siteId = cmd.siteId.getOrElse(current.siteId)
            controllers
              .at(host.trim, port, siteId.trim)
              .map: there =>
                Decided:
                  for
                    _ <- Decided.unless(
                      there.filter(_ != cmd.id).map(LocationError.ControllerExists(_)))
                    changed <- Controller.of(
                      cmd.id,
                      host,
                      port,
                      siteId,
                      cmd.token.getOrElse(current.token))
                  yield (LocationEvent.ControllerChanged(changed, cmd.occurredAt), changed)
end UpdateControllerHandler

final case class RemoveControllerCommand(id: ControllerId, occurredAt: Instant)

/** Refused while a location is configured with an access point that answers to the controller: it
  * is moved to another first, or taken off every location. Those no location is configured with go
  * with it.
  */
object RemoveControllerHandler:
  def apply[F[_]: Monad](
      controllers: ControllerRepository[F]): Handler.Aux[F, RemoveControllerCommand, Unit] =
    new Handler[F, RemoveControllerCommand]:
      type Result = Unit

      def handle(cmd: RemoveControllerCommand) = controllers
        .find(cmd.id)
        .flatMap:
          case None =>
            Monad[F].pure(Decided(Decided.refused(LocationError.NoSuchController(cmd.id))))
          case Some(_) =>
            controllers
              .uses(cmd.id)
              .map: uses =>
                Decided:
                  Decided
                    .unless(Option.when(uses > 0)(LocationError.ControllerInUse(cmd.id, uses)))
                    .map(_ => (LocationEvent.ControllerRemoved(cmd.id, cmd.occurredAt), ()))
end RemoveControllerHandler
