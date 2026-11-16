package whitelabel.captal.core.advertiser

import java.time.Instant

import whitelabel.captal.core.survey.AdvertiserId

/** A sponsor whose videos the portals show: `priority` weights its turn in the rotation against the
  * other active advertisers, zero never drawing it. A retired one keeps its videos and the answers
  * given to them, but is drawn by nothing.
  */
final case class Advertiser(id: AdvertiserId, name: String, priority: Int, active: Boolean)

enum Event:
  case AdvertiserCreated(advertiser: Advertiser, occurredAt: Instant)
  case AdvertiserUpdated(id: AdvertiserId, name: String, priority: Int, occurredAt: Instant)

  /** Out of the rotation, and its videos with it. */
  case AdvertiserRetired(id: AdvertiserId, occurredAt: Instant)

  /** Back in the rotation, with its videos that are. */
  case AdvertiserReinstated(id: AdvertiserId, occurredAt: Instant)
end Event

enum Error:
  case NotFound(id: String)
  case BlankName

  /** Below zero, which no rotation can weight. */
  case NegativePriority(priority: Int)

  /** Another advertiser, `by`, has the name: nothing else tells two apart. */
  case NameTaken(name: String, by: AdvertiserId)

  /** A change that names neither a name nor a priority. */
  case NothingToUpdate
end Error
