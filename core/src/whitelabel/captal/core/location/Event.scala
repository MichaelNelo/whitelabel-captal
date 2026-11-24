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

  /** A controller the system did not have, which access points can answer to from now on. */
  case ControllerRegistered(controller: Controller, occurredAt: Instant)

  /** The controller is now `controller`: every access point that answers to it, whatever location
    * has it, calls it there.
    */
  case ControllerChanged(controller: Controller, occurredAt: Instant)

  /** The controller, which no access point answered to, is gone. */
  case ControllerRemoved(id: ControllerId, occurredAt: Instant)

  /** The run `by` stood the location up, from created or destroyed. */
  case LocationDeployed(id: LocationId, by: RunId, occurredAt: Instant)
  case LocationPaused(id: LocationId, by: RunId, occurredAt: Instant)
  case LocationResumed(id: LocationId, by: RunId, occurredAt: Instant)
  case LocationDestroyed(id: LocationId, by: RunId, occurredAt: Instant)

  /** The location's portal is styled by `css` from now on, in place of the stylesheet it had. */
  case StylesChanged(id: LocationId, slug: Slug, css: String, occurredAt: Instant)

  /** The location's portal shows `svg` as its brand icon from now on, on every screen. */
  case IconChanged(id: LocationId, slug: Slug, svg: String, occurredAt: Instant)
end Event
