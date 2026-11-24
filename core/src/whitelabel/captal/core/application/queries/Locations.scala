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

/** The access points the location `slug` is configured with. */
final case class AccessPointsOfQuery(slug: Slug)

/** An access point a location is configured with: the controller it answers to, whether the
  * controller's token is stored — never the token —, what the location does for the guests who join
  * through it, and the location that took it, if one has.
  */
final case class AccessPointReport(
    apMac: String,
    controllerId: String,
    host: String,
    port: Option[Int],
    siteId: String,
    tokenSet: Boolean,
    durationMinutes: Option[Int],
    redirectUrl: Option[String],
    enabled: Boolean,
    takenBy: Option[Taker])

final case class AccessPointsReport(
    slug: String,
    name: String,
    accessPoints: List[AccessPointReport])

/** The location `slug` that took an access point, and how far: `deploying` while the deploy or the
  * resume that took it runs, `deployed` once it succeeded.
  */
final case class Taker(slug: String, state: String)

/** The system's UniFi controllers. */
final case class ControllersQuery()

/** A controller, whether its token is stored — never the token —, and how many access points a
  * location is configured with answer to it.
  */
final case class ControllerReport(
    id: String,
    host: String,
    port: Option[Int],
    siteId: String,
    tokenSet: Boolean,
    accessPoints: Int)

/** Every access point the system has, whichever locations are configured with it. */
final case class AccessPointsQuery()

/** An access point, the controller it answers to, the locations configured with it, and the one
  * that took it, if one has.
  */
final case class KnownAccessPoint(
    mac: String,
    controllerId: String,
    locations: List[String],
    takenBy: Option[Taker])

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
