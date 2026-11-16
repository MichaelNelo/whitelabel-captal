package whitelabel.captal.core.application.queries

/** The advertisers, retired ones only when `includeInactive`. */
final case class ListAdvertisersQuery(includeInactive: Boolean)

/** An advertiser and how many of its videos are in rotation: none with `active` true means it can
  * be drawn and has nothing to show.
  */
final case class AdvertiserReport(
    id: String,
    name: String,
    priority: Int,
    active: Boolean,
    activeVideos: Int)
