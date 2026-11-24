package whitelabel.captal.core.application.queries

import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{Destination, LocationId, Slug}

/** The locations, retired ones only with `includeInactive`. */
final case class ListLocationsQuery(includeInactive: Boolean)

/** A location, with whether it has an access point: without one every guest is turned away. */
final case class LocationSummary(
    slug: String,
    name: String,
    active: Boolean,
    unifiConfigured: Boolean,
    fingerprintRequired: Boolean)

/** The access points of the location `slug`. */
final case class AccessPointsOfQuery(slug: Slug)

/** An access point with its controller, and whether its token is stored, never the token. */
final case class AccessPointReport(
    apMac: String,
    host: String,
    port: Option[Int],
    siteId: String,
    tokenSet: Boolean,
    durationMinutes: Option[Int],
    redirectUrl: Option[String],
    enabled: Boolean)

final case class AccessPointsReport(
    slug: String,
    name: String,
    accessPoints: List[AccessPointReport])

/** Where access points' guests are sent (`Dispatch.destination`): every MAC a location holds, the
  * MACs given, or the MACs of one location. Every MAC asked for is answered, held or not.
  */
enum DispatchQuery:
  case All
  case Of(macs: Set[Mac])
  case OfLocation(id: LocationId)

/** An access point's MAC and where its guests are sent: none when no location holding it is
  * deployed or paused, or no location holds it any more.
  */
final case class DispatchRoute(mac: Mac, destination: Option[Destination])

/** What the portal of the location `slug` draws with, in `locale`. */
final case class PreviewLocationQuery(slug: Slug, locale: String)

/** The location's stylesheet — none for the base theme — and icon, the portal's own when it has
  * none; its texts in the locale by key, `untranslated` of the required ones missing; and the
  * locales the portal offers.
  */
final case class LocationPreview(
    slug: String,
    name: String,
    locale: String,
    stylesheet: Option[String],
    icon: String,
    texts: Map[String, String],
    locales: List[String],
    untranslated: Int)
