package whitelabel.captal.core.session

import java.time.Instant

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.user

enum Event:
  /** A guest's session started, on its device, under its user when the guest is a known one, at the
    * welcome already decided, with what the redirect said: the MAC access is granted to, the access
    * point it came through, where UniFi sends the guest afterwards, and the fingerprint the device
    * was matched by.
    */
  case SessionStarted(
      sessionId: user.SessionId,
      deviceId: user.DeviceId,
      userId: Option[user.Id],
      locationId: Option[String],
      locale: String,
      phase: Phase,
      clientMac: Mac,
      apMac: Option[Mac],
      redirectUrl: String,
      ssid: String,
      fingerprint: String,
      occurredAt: Instant)

  /** A session at the welcome moved between `Welcome` and `WelcomeNeedsToS`: its device accepted
    * the terms in force since, or the version in force changed.
    */
  case WelcomeResolved(sessionId: user.SessionId, phase: Phase, occurredAt: Instant)
end Event
