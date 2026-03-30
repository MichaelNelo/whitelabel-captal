package whitelabel.captal.infra.services

import io.getquill.*
import whitelabel.captal.infra.LocationRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*

trait LocationService:
  def findBySlug(slug: String): Task[Option[LocationRow]]

object LocationService:

  /** Error raised when the configured LOCATION_SLUG does not match any row in the locations table.
    * This is a configuration error — the slug was set but the location hasn't been provisioned yet.
    */
  final case class LocationNotFound(slug: String)
      extends RuntimeException(
        s"Location '$slug' not found. Verify LOCATION_SLUG is correct and the location has been provisioned.")

  inline def findBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  def apply(quill: QuillSqlite): LocationService =
    new LocationService:
      import quill.*

      def findBySlug(slug: String): Task[Option[LocationRow]] =
        run(findBySlugQuery(lift(slug))).map(_.headOption).orDie

  /** Resolve a slug to a location ID, failing with LocationNotFound if not found. */
  def resolveSlug(slug: String): ZIO[LocationService, LocationNotFound, String] =
    ZIO
      .serviceWithZIO[LocationService](_.findBySlug(slug))
      .orDie
      .flatMap:
        case Some(row) => ZIO.succeed(row.id)
        case None      => ZIO.fail(LocationNotFound(slug))

  val layer: ZLayer[QuillSqlite, Nothing, LocationService] = ZLayer.fromFunction(apply)
end LocationService
