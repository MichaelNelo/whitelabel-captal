package whitelabel.captal.platform.services

import io.getquill.*
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

trait LocationService:
  def findBySlug(slug: String): Task[Option[LocationRow]]

  /** Whether the location with this id has its UniFi calls enabled, read now rather than at
    * startup. False when no such location exists.
    */
  def unifiEnabled(id: String): Task[Boolean]

  /** Whether the location with this id requires the redirect's fingerprint, read now rather than at
    * startup. True when no such location exists.
    */
  def fingerprintRequired(id: String): Task[Boolean]

object LocationService:

  /** Error raised when the configured LOCATION_SLUG matches no row in the locations table. */
  final case class LocationNotFound(slug: String)
      extends RuntimeException(
        s"Location '$slug' not found. Verify LOCATION_SLUG is correct and the location has been provisioned.")

  inline def findBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  inline def unifiEnabledQuery = quote: (idParam: String) =>
    query[LocationRow].filter(_.id == idParam).map(_.unifiIsEnabled).take(1)

  inline def fingerprintRequiredQuery = quote: (idParam: String) =>
    query[LocationRow].filter(_.id == idParam).map(_.fingerprintIsRequired).take(1)

  def apply(quill: QuillSqlite): LocationService =
    new LocationService:
      import quill.*

      def findBySlug(slug: String): Task[Option[LocationRow]] =
        run(findBySlugQuery(lift(slug))).map(_.headOption).orDie

      def unifiEnabled(id: String): Task[Boolean] = run(unifiEnabledQuery(lift(id))).map(
        _.headOption.contains(1))

      def fingerprintRequired(id: String): Task[Boolean] = run(fingerprintRequiredQuery(lift(id)))
        .map(!_.headOption.contains(0))

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
