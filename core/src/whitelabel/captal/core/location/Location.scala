package whitelabel.captal.core.location

opaque type LocationId = String

object LocationId:
  def apply(value: String): LocationId = value

  extension (id: LocationId)
    def asString: String = id

/** What a location's portal does that is not content, the same in every state: how long a guest is
  * let in for (`None`, the default), where they are sent afterwards, whether its UniFi calls are
  * made at all, and whether a redirect has to carry the link shortener's fingerprint.
  */
final case class Settings(
    durationMinutes: Option[Int],
    redirectUrl: Option[String],
    unifiEnabled: Boolean,
    fingerprintRequired: Boolean)

object Settings:
  /** A new location's: UniFi on, a fingerprint required, nothing else set. */
  val Default: Settings = Settings(
    durationMinutes = None,
    redirectUrl = None,
    unifiEnabled = true,
    fingerprintRequired = true)

/** A location, by where it stands ([[State]]). */
final case class Location[S <: State](
    id: LocationId,
    slug: Slug,
    name: String,
    settings: Settings,
    state: S)
