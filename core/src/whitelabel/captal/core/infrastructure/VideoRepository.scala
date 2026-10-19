package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{user, video}

trait VideoRepository[F[_]]:
  def findById(id: video.Id): F[Option[video.AdvertiserVideo]]

  /** The next video to show: an advertiser video when one qualifies, otherwise a promo. Reaching
    * the video phase without a user is a broken invariant, not a case to serve. The promo rotation
    * cursor is not a parameter — it lives on the session row, which only the implementation reads.
    */
  def findNextForUser(userId: user.Id): F[Option[VideoToWatch]]
