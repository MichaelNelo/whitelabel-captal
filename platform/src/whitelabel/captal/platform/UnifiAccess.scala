package whitelabel.captal.platform

/** UniFi Controller access derived from a [[LocationRow]] for the Integration v1 API.
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

  /** Build from a LocationRow when `host`, `apiToken` and `siteId` are all present; `None`
    * otherwise.
    */
  def fromRow(row: LocationRow): Option[UnifiAccess] =
    for
      host   <- row.unifiHost
      token  <- row.unifiApiToken
      siteId <- row.unifiSiteId
    yield UnifiAccess(
      host = host,
      apiToken = token,
      port = row.unifiPort.getOrElse(DefaultPort),
      siteId = siteId,
      defaultDurationMinutes = row.unifiDurationMinutes.getOrElse(DefaultDurationMinutes)
    )
end UnifiAccess
