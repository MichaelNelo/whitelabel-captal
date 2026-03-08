package whitelabel.captal.core.video

import java.time.Instant

import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.user

enum Event:
  case VideoVisualized(
      sessionId: user.SessionId,
      userId: Option[user.Id],
      videoId: Id,
      advertiserId: Option[AdvertiserId],
      videoType: VideoType,
      durationWatched: Int,
      completed: Boolean,
      occurredAt: Instant)
