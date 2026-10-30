package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{user, video}

trait VideoRepository[F[_]]:
  /** The video with its current file: the newest confirmed one. */
  def findById(id: video.Id): F[Option[video.AdvertiserVideo]]

  /** The next video to show: an advertiser video when one qualifies, otherwise the location's promo
    * this user has watched least. Reaching the video phase without a user is a broken invariant,
    * not a case to serve.
    */
  def findNextForUser(userId: user.Id): F[Option[VideoToWatch]]

  /** The session's playbacks not yet ended. */
  def openPlaybacks(sessionId: user.SessionId): F[List[video.PlaybackId]]
