package whitelabel.captal.api

import whitelabel.captal.infra.LocationRow
import whitelabel.captal.infra.services.LocationService
import zio.*

/** UniFi Controller access derived from the location row. Defaults are applied here so that
  * `port`, `site`, `unifiOs` and `defaultDurationMinutes` always have concrete values in code,
  * even when the operator left them implicit in YAML. The DB column may be NULL — meaning "use
  * the project-wide default" — while this case class always carries the resolved value.
  */
final case class UnifiAccess(
    host: String,
    apiToken: String,
    port: Int,
    site: String,
    unifiOs: Boolean,
    defaultDurationMinutes: Int)

object UnifiAccess:
  private val DefaultPort = 8443
  private val DefaultSite = "default"
  private val DefaultUnifiOs = true
  private val DefaultDurationMinutes = 1440

  /** Build from a LocationRow if both required fields (`host`, `apiToken`) are present. */
  def fromRow(row: LocationRow): Option[UnifiAccess] =
    for
      host  <- row.unifiHost
      token <- row.unifiApiToken
    yield UnifiAccess(
      host = host,
      apiToken = token,
      port = row.unifiPort.getOrElse(DefaultPort),
      site = row.unifiSite.getOrElse(DefaultSite),
      unifiOs = row.unifiUseOs.map(_ != 0).getOrElse(DefaultUnifiOs),
      defaultDurationMinutes = row.unifiDurationMinutes.getOrElse(DefaultDurationMinutes))
end UnifiAccess

/** Snapshot of the location this API instance serves. Loaded once at startup from the
  * `LOCATION_SLUG` env var → DB lookup. Empty when there is no slug (dev/standalone mode).
  *
  * Used to soft-validate inbound captive-portal headers (e.g. compare `X-Ap-Mac` with the
  * provisioned `location.ap_mac`) without requiring a per-request DB query.
  */
final case class CurrentLocation(
    id: Option[String],
    slug: Option[String],
    apMac: Option[String],
    unifi: Option[UnifiAccess])

object CurrentLocation:
  val empty: CurrentLocation = CurrentLocation(None, None, None, None)

  /** Build a layer for the given slug. Looks up the LocationRow once and snapshots
    * `(id, slug, apMac, unifi)`. Fails if slug is set but row is missing.
    */
  def make(slug: Option[String]): ZLayer[LocationService, Throwable, CurrentLocation] = ZLayer
    .fromZIO:
      slug match
        case None =>
          ZIO.succeed(empty)
        case Some(s) =>
          ZIO
            .serviceWithZIO[LocationService](_.findBySlug(s))
            .someOrFail(LocationService.LocationNotFound(s))
            .map: row =>
              CurrentLocation(Some(row.id), Some(row.slug), row.apMac, UnifiAccess.fromRow(row))
end CurrentLocation
