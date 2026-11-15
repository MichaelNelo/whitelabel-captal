package whitelabel.captal.platform.repositories

import cats.data.NonEmptyList
import io.getquill.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{AccessPointRepository, LocationRepository}
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
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
import whitelabel.captal.platform.{AccessPointRow, LocationRow, LocationStates}
import zio.*

/** Locations and their access points as `locations` and `access_points` hold them. A row the model
  * cannot hold — a deployed location with no access point, an access point with no token — is a
  * defect, since no command writes one.
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

      def of(location: LocationId): Task[List[AccessPoint]] =
        run(query[AccessPointRow].filter(_.locationId == lift(location.asString)))
          .map(_.map(accessPointOf))
          .orDie

      def deployedHolders(mac: Mac): Task[List[Slug]] =
        run(
          for
            point    <- query[AccessPointRow].filter(_.mac == lift(mac.show))
            location <- query[LocationRow].join(l =>
              l.id == point.locationId && l.state == lift(LocationStates.Deployed))
          yield location.slug).map(_.distinct.map(Slug.unsafe)).orDie
    end new
  end apply

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
      Settings(
        durationMinutes = row.unifiDurationMinutes,
        redirectUrl = row.unifiRedirectUrl,
        unifiEnabled = row.unifiIsEnabled == 1,
        fingerprintRequired = row.fingerprintIsRequired == 1
      ),
      state
    )
  end locationOf

  private def accessPointOf(row: AccessPointRow): AccessPoint = AccessPoint(
    Mac.parse(row.mac).getOrElse(defect(s"Access point ${row.id} has no MAC: '${row.mac}'")),
    Controller(
      row.unifiHost,
      row.unifiPort,
      row.unifiSiteId,
      Secret.parse(row.unifiApiToken).getOrElse(defect(s"Access point ${row.mac} has no token")))
  )

  private def defect(what: String): Nothing = throw IllegalStateException(what)
end LocationRepositoryQuill
