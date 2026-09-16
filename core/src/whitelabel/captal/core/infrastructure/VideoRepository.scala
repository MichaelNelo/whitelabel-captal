package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{user, video}

trait VideoRepository[F[_]]:
  def findById(id: video.Id): F[Option[video.AdvertiserVideo]]

  /** The next video to show: an advertiser video when one qualifies, otherwise a promo.
    *
    * The visitor is identified by construction — reaching the video phase without a user is a
    * broken invariant, not a case to serve.
    *
    * The promo rotation cursor is deliberately not a parameter. It lives on the session row, which
    * the implementation reads directly; core has no access to the session, which is why the caller
    * used to pass a hardcoded None with a TODO.
    */
  def findNextForUser(userId: user.Id): F[Option[VideoToWatch]]
