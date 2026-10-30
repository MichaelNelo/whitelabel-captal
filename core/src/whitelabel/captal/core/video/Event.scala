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

  case PlaybackStarted(
      playbackId: PlaybackId,
      sessionId: user.SessionId,
      videoId: Id,
      fileUrl: String,
      occurredAt: Instant)

  case PlaybackEnded(playbackId: PlaybackId, sessionId: user.SessionId, occurredAt: Instant)
