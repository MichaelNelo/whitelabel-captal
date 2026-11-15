package whitelabel.captal.core.location

opaque type LocationId = String

object LocationId:
  def apply(value: String): LocationId = value

  extension (id: LocationId)
    def asString: String = id

/** A location, by where it stands ([[State]]). What its portal does for a guest — how long they are
  * let in for, where they are sent — is its access points' to say, since each answers to its own
  * controller ([[AccessPoint]]).
  */
final case class Location[S <: State](id: LocationId, slug: Slug, name: String, state: S)
