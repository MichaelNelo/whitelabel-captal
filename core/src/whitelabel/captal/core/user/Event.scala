package whitelabel.captal.core.user

import java.time.Instant

enum Event:
  case UserCreated(
      userId: Id,
      email: Email,
      sessionId: SessionId,
      deviceId: DeviceId,
      locale: String,
      occurredAt: Instant)
