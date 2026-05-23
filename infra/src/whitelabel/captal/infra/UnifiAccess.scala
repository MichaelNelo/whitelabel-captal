package whitelabel.captal.infra

/** UniFi Controller access derived from a [[LocationRow]]. Defaults are applied here so that
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
