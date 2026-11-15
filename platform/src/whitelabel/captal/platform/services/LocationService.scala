package whitelabel.captal.platform.services

import io.getquill.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.platform.{AccessPointRow, LocationRow, UnifiAccess}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

trait LocationService:
  def findBySlug(slug: String): Task[Option[LocationRow]]

  /** Whether the location's access point with this MAC has its UniFi calls enabled, read now rather
    * than at startup. True when it has no such AP, which [[unifiFor]] answers with none.
    */
  def unifiEnabled(id: String, ap: Mac): Task[Boolean]

  /** The UniFi access of the location's access point with this MAC — its controller, granting its
    * access duration — read now rather than at startup. None when it has no such AP.
    */
  def unifiFor(id: String, ap: Mac): Task[Option[UnifiAccess]]
end LocationService

object LocationService:

  /** Error raised when the configured LOCATION_SLUG matches no row in the locations table. */
  final case class LocationNotFound(slug: String)
      extends RuntimeException(
        s"Location '$slug' not found. Verify LOCATION_SLUG is correct and the location has been provisioned.")

  inline def findBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  inline def accessPointQuery = quote: (idParam: String, macParam: String) =>
    query[AccessPointRow].filter(ap => ap.locationId == idParam && ap.mac == macParam).take(1)

  def apply(quill: QuillSqlite): LocationService =
    new LocationService:
      import quill.*

      private def accessPoint(id: String, ap: Mac): Task[Option[AccessPointRow]] = run(
        accessPointQuery(lift(id), lift(ap.show))).map(_.headOption)

      def findBySlug(slug: String): Task[Option[LocationRow]] =
        run(findBySlugQuery(lift(slug))).map(_.headOption).orDie

      def unifiEnabled(id: String, ap: Mac): Task[Boolean] = accessPoint(id, ap).map(
        !_.exists(_.unifiIsEnabled == 0))

      def unifiFor(id: String, ap: Mac): Task[Option[UnifiAccess]] = accessPoint(id, ap).map(
        _.map(UnifiAccess.of))

  /** Resolve a slug to a location ID, failing with LocationNotFound if not found. */
  def resolveSlug(slug: String): ZIO[LocationService, LocationNotFound, String] = ZIO
    .serviceWithZIO[LocationService](_.findBySlug(slug))
    .orDie
    .flatMap:
      case Some(row) =>
        ZIO.succeed(row.id)
      case None =>
        ZIO.fail(LocationNotFound(slug))

  val layer: ZLayer[QuillSqlite, Nothing, LocationService] = ZLayer.fromFunction(apply)
end LocationService
