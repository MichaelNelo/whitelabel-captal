package whitelabel.captal.core.location

import java.time.Instant

import whitelabel.captal.core.device.Mac

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
