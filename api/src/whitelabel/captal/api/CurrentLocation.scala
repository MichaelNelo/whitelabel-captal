package whitelabel.captal.api

import whitelabel.captal.infra.services.LocationService
import zio.*

/** Snapshot of the location this API instance serves. Loaded once at startup from the
  * `LOCATION_SLUG` env var → DB lookup. Empty when there is no slug (dev/standalone mode).
  *
  * Used to soft-validate inbound captive-portal headers (e.g. compare `X-Ap-Mac` with the
  * provisioned `location.ap_mac`) without requiring a per-request DB query.
  */
final case class CurrentLocation(
    id: Option[String],
    slug: Option[String],
    apMac: Option[String])

object CurrentLocation:
  val empty: CurrentLocation = CurrentLocation(None, None, None)

  /** Build a layer for the given slug. Looks up the LocationRow once and snapshots
    * `(id, slug, apMac)`. Fails if slug is set but row is missing.
    */
  def make(slug: Option[String]): ZLayer[LocationService, Throwable, CurrentLocation] =
    ZLayer.fromZIO:
      slug match
        case None =>
          ZIO.succeed(empty)
        case Some(s) =>
          ZIO
            .serviceWithZIO[LocationService](_.findBySlug(s))
            .someOrFail(LocationService.LocationNotFound(s))
            .map(row => CurrentLocation(Some(row.id), Some(row.slug), row.apMac))
end CurrentLocation
