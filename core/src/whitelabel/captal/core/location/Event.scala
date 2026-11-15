package whitelabel.captal.core.location

import java.time.Instant

import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.operation.RunId

enum Event:
  case LocationCreated(id: LocationId, slug: Slug, name: String, occurredAt: Instant)
  case FingerprintRequirementChanged(id: LocationId, required: Boolean, occurredAt: Instant)
  case AccessPointRegistered(location: LocationId, accessPoint: AccessPoint, occurredAt: Instant)

  /** The access point that had the MAC `mac` is now `accessPoint`, whose MAC may differ. */
  case AccessPointUpdated(
      location: LocationId,
      mac: Mac,
      accessPoint: AccessPoint,
      occurredAt: Instant)

  case AccessPointRemoved(location: LocationId, mac: Mac, occurredAt: Instant)

  /** The run `by` stood the location up, from created or destroyed. */
  case LocationDeployed(id: LocationId, by: RunId, occurredAt: Instant)
  case LocationPaused(id: LocationId, by: RunId, occurredAt: Instant)
  case LocationResumed(id: LocationId, by: RunId, occurredAt: Instant)
  case LocationDestroyed(id: LocationId, by: RunId, occurredAt: Instant)
