package whitelabel.captal.core.infrastructure

import java.time.{Duration, Instant}

import whitelabel.captal.core.user
import whitelabel.captal.core.video.PlaybackId

/** A playback not ended yet, with what its deadline is reckoned from: when it started, the length
  * of the file it plays and the grace of the location its session is at.
  */
final case class OpenPlayback(
    id: PlaybackId,
    sessionId: user.SessionId,
    startedAt: Instant,
    fileDurationSeconds: Int,
    grace: Duration)

trait PlaybackRepository[F[_]]:
  /** The playback `id`, while it is open. */
  def open(id: PlaybackId): F[Option[OpenPlayback]]
