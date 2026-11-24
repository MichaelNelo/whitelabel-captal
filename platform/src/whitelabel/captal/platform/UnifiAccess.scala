package whitelabel.captal.platform

/** UniFi Controller access for the Integration v1 API: an access point's controller, with the
  * access duration of the location configured with it.
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

  /** The access through `controller`, granting the duration the location is `configured` with, 1440
    * when unset.
    */
  def of(controller: UnifiControllerRow, configured: LocationAccessPointRow): UnifiAccess =
    UnifiAccess(
      host = controller.host,
      apiToken = controller.apiToken,
      port = controller.port.getOrElse(DefaultPort),
      siteId = controller.siteId,
      defaultDurationMinutes = configured.durationMinutes.getOrElse(DefaultDurationMinutes)
    )
end UnifiAccess
