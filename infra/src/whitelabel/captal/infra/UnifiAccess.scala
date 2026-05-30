package whitelabel.captal.infra

/** UniFi Controller access derived from a [[LocationRow]] for the Integration v1 API.
  *
  * `siteId` is the UUID returned by `GET /proxy/network/integration/v1/sites` on the controller.
  * The legacy site name ("default") does NOT work — the operator looks it up once with curl and
  * pastes the UUID into `location.yaml`. `host` is the UCG address; `port` defaults to 443 (the
  * Integration v1 API is served behind the same UI-managed cert as the controller UI).
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

  /** Build from a LocationRow if all required fields (`host`, `apiToken`, `siteId`) are present.
    * Returns `None` when any is missing — the handler then logs and skips, leaving the session in
    * Phase.Ready.
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
