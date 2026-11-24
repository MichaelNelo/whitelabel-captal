package whitelabel.captal.platform.repositories

import cats.data.NonEmptyList
import io.getquill.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{
  AccessPointRepository,
  ControllerRepository,
  LocationRepository
}
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
  ControllerId,
  Location,
  LocationId,
  Secret,
  Settings,
  Slug,
  State
}
import whitelabel.captal.core.operation.RunId
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AccessPointRow,
  ActiveAccessPointRow,
  LocationAccessPointRow,
  LocationRow,
  LocationStates,
  UnifiControllerRow
}
import zio.*

/** Locations and the access points they are configured with, as `locations`,
  * `location_access_points`, `access_points` and `unifi_controllers` hold them; which location has
  * taken an access point, as `active_access_points` does. A row the model cannot hold — a deployed
  * location with no access point, a controller with no token — is a defect, since no command writes
  * one.
  */
object LocationRepositoryQuill:
  def apply(quill: QuillSqlite): LocationRepository[Task] & AccessPointRepository[Task] =
    import quill.*
    new LocationRepository[Task] with AccessPointRepository[Task]:
      def find(slug: Slug): Task[Option[Location[State]]] =
        run(query[LocationRow].filter(_.slug == lift(slug.asString)))
          .map(_.headOption)
          .flatMap(ZIO.foreach(_)(row => of(LocationId(row.id)).map(locationOf(row, _))))
          .orDie

      def byId(id: LocationId): Task[Option[Location[State]]] =
        run(query[LocationRow].filter(_.id == lift(id.asString)))
          .map(_.headOption)
          .flatMap(ZIO.foreach(_)(row => of(id).map(locationOf(row, _))))
          .orDie

      /** None too for a deployed row with no access point, which no command writes: it would admit
        * no guest anyway.
        */
      def findDeployed(id: LocationId): Task[Option[Location[State.Deployed]]] = run(
        query[LocationRow].filter(row =>
          row.id == lift(id.asString) && row.state == lift(LocationStates.Deployed)))
        .map(_.headOption)
        .flatMap(ZIO.foreach(_)(row => of(id).map(row -> _)))
        .orDie
        .map(
          _.collect {
            case (row, points) if points.nonEmpty =>
              locationOf(row, points) match
                case Location(
                      locationId,
                      slug,
                      name,
                      fingerprintRequired,
                      deployed: State.Deployed) =>
                  Location(locationId, slug, name, fingerprintRequired, deployed)
                case other =>
                  defect(s"'${other.slug.asString}' read as deployed is ${other.state}")
          })

      def of(location: LocationId): Task[List[AccessPoint]] =
        run(
          for
            configured <- query[LocationAccessPointRow].filter(
              _.locationId == lift(location.asString))
            point      <- query[AccessPointRow].join(_.id == configured.accessPointId)
            controller <- query[UnifiControllerRow].join(_.id == point.controllerId)
          yield (configured, point, controller))
          .map(_.map(accessPointOf).sortBy(_.mac.toLong))
          .orDie

      def takenBy(mac: Mac): Task[Option[Slug]] =
        run(
          for
            point    <- query[AccessPointRow].filter(_.mac == lift(mac.show))
            taken    <- query[ActiveAccessPointRow].join(_.accessPointId == point.id)
            location <- query[LocationRow].join(_.id == taken.locationId)
          yield location.slug).map(_.headOption.map(Slug.unsafe)).orDie

      def controllerOf(mac: Mac): Task[Option[Controller]] =
        run(
          for
            point      <- query[AccessPointRow].filter(_.mac == lift(mac.show))
            controller <- query[UnifiControllerRow].join(_.id == point.controllerId)
          yield controller).map(_.headOption.map(LocationRepositoryQuill.controllerOf)).orDie
    end new
  end apply

  /** The UniFi controllers `unifi_controllers` holds. */
  def controllers(quill: QuillSqlite): ControllerRepository[Task] =
    import quill.*
    new ControllerRepository[Task]:
      def find(id: ControllerId): Task[Option[Controller]] =
        run(query[UnifiControllerRow].filter(_.id == lift(id.asString)))
          .map(_.headOption.map(controllerOf))
          .orDie

      def at(host: String, port: Option[Int], siteId: String): Task[Option[ControllerId]] =
        run(
          query[UnifiControllerRow].filter(row =>
            row.host == lift(host) && row.siteId == lift(siteId)))
          .map(
            _.find(_.port.getOrElse(443) == port.getOrElse(443)).map(row => ControllerId(row.id)))
          .orDie

      def uses(id: ControllerId): Task[Int] =
        run(
          query[AccessPointRow]
            .filter(point =>
              point.controllerId == lift(id.asString) &&
                query[LocationAccessPointRow].filter(_.accessPointId == point.id).nonEmpty)
            .size).map(_.toInt).orDie
    end new
  end controllers

  private def locationOf(row: LocationRow, points: List[AccessPoint]): Location[State] =
    def run = RunId(row.stateRun.getOrElse(defect(s"'${row.slug}' is ${row.state} by no run")))
    val state: State =
      row.state match
        case LocationStates.Created =>
          State.Created
        case LocationStates.Deployed =>
          State.Deployed(
            run,
            NonEmptyList
              .fromList(points)
              .getOrElse(defect(s"'${row.slug}' is deployed with no access point")))
        case LocationStates.Paused =>
          State.Paused(run)
        case LocationStates.Destroyed =>
          State.Destroyed(run)
        case other =>
          defect(s"'${row.slug}' is in no state the model knows: '$other'")
    Location(
      LocationId(row.id),
      Slug.unsafe(row.slug),
      row.name,
      fingerprintRequired = row.fingerprintIsRequired == 1,
      state)
  end locationOf

  /** A location's view of an access point it is configured with: the access point, its controller,
    * and what the location does for its guests.
    */
  private[platform] def accessPointOf(
      configured: LocationAccessPointRow,
      point: AccessPointRow,
      controller: UnifiControllerRow): AccessPoint = AccessPoint(
    Mac.parse(point.mac).getOrElse(defect(s"Access point ${point.id} has no MAC: '${point.mac}'")),
    controllerOf(controller),
    Settings(
      durationMinutes = configured.durationMinutes,
      redirectUrl = configured.redirectUrl,
      unifiEnabled = configured.unifiIsEnabled == 1)
  )

  private[platform] def controllerOf(row: UnifiControllerRow): Controller = Controller(
    ControllerId(row.id),
    row.host,
    row.port,
    row.siteId,
    Secret.parse(row.apiToken).getOrElse(defect(s"Controller ${row.id} has no token")))

  private def defect(what: String): Nothing = throw IllegalStateException(what)
end LocationRepositoryQuill
