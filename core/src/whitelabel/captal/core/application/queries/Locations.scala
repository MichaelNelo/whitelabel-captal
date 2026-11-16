package whitelabel.captal.core.application.queries

import whitelabel.captal.core.location.Slug

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
