package whitelabel.captal.core.device

import java.time.Instant

import whitelabel.captal.core.Op
import whitelabel.captal.core.infrastructure.TermsVersion

object ops:
  extension (device: Device[State.NotAccepted.type])
    /** Accepts `terms`, the version in force: only a device that has not can. It cannot fail, so
      * the caller names the error type of the operation it joins.
      */
    def acceptTerms[E](terms: TermsVersion, now: Instant): Op[Event, E, Device[State.Accepted]] = Op
      .emit(
        Event.TermsAccepted(device.id, terms.id, now),
        Device(device.id, State.Accepted(terms.id, now)))
