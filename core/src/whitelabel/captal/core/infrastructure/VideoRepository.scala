package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{user, video}

trait VideoRepository[F[_]]:
  def findById(id: video.Id): F[Option[video.AdvertiserVideo]]

  /** The visitor is identified by construction: reaching the video phase without a user is a broken
    * invariant, not a case to serve. Callers that have no user must not ask.
    */
  def findNextForUser(userId: user.Id, lastPromoVideoId: Option[video.Id]): F[Option[VideoToWatch]]
