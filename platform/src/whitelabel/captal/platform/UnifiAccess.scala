package whitelabel.captal.platform

/** UniFi Controller access for the Integration v1 API: an [[AccessPointRow]]'s controller, with its
  * access duration.
  *
  * `siteId` is the UUID returned by `GET /proxy/network/integration/v1/sites` on the controller;
  * the legacy site name ("default") does NOT work. `host` is the UCG address, `port` defaults to
  * 443.
  */
final case class UnifiAccess(
    host: String,
    apiToken: String,
    port: Int,
    siteId: String,
    defaultDurationMinutes: Int)

object UnifiAccess:
  private val DefaultPort = 443
  private val DefaultDurationMinutes = 1440

  /** The access through `ap`, granting its duration, 1440 when unset. */
  def of(ap: AccessPointRow): UnifiAccess = UnifiAccess(
    host = ap.unifiHost,
    apiToken = ap.unifiApiToken,
    port = ap.unifiPort.getOrElse(DefaultPort),
    siteId = ap.unifiSiteId,
    defaultDurationMinutes = ap.durationMinutes.getOrElse(DefaultDurationMinutes)
  )
end UnifiAccess
