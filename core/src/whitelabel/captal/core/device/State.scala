package whitelabel.captal.core.device

import java.time.Instant

import whitelabel.captal.core.user

/** A device, by where it stands with the terms of service in force. */
final case class Device[S <: State](id: user.DeviceId, state: S)

enum State:
  /** It never accepted the terms, or accepted a version that is no longer in force. */
  case NotAccepted

  /** It accepted the version in force, `termsId`, at `acceptedAt`. */
  case Accepted(termsId: String, acceptedAt: Instant)
