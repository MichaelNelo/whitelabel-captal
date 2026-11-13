package whitelabel.captal.api.session

import whitelabel.captal.platform.services.LocationService
import zio.*

/** The location this API instance serves, resolved once at startup from the `LOCATION_SLUG` env var
  * via a DB lookup. Empty when there is no slug (dev/standalone mode).
  *
  * Only which location it is: everything else about it — its access points and their UniFi
  * controllers, its switches — is read from the database when it is needed, so the backoffice
  * changes it without a restart.
  */
final case class CurrentLocation(id: Option[String], slug: Option[String])

object CurrentLocation:
  val empty: CurrentLocation = CurrentLocation(None, None)

  /** Build a layer for the given slug. Fails if slug is set but row is missing. */
  def make(slug: Option[String]): ZLayer[LocationService, Throwable, CurrentLocation] = ZLayer
    .fromZIO:
      slug match
        case None =>
          ZIO.succeed(empty)
        case Some(s) =>
          ZIO
            .serviceWithZIO[LocationService](_.findBySlug(s))
            .someOrFail(LocationService.LocationNotFound(s))
            .map(row => CurrentLocation(Some(row.id), Some(row.slug)))
end CurrentLocation
