package whitelabel.captal.core.user

import java.time.Instant

enum Event:
  case SessionStarted(
      userId: UserId,
      sessionId: SessionId,
      deviceId: DeviceId,
      locale: String,
      occurredAt: Instant)

  case EmailRegistered(userId: UserId, email: Email, sessionId: SessionId, occurredAt: Instant)

  case EmailValidated(userId: UserId, email: Email, occurredAt: Instant)
