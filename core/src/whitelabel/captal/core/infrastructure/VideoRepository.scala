package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{user, video}

trait VideoRepository[F[_]]:
  def findById(id: video.Id): F[Option[video.AdvertiserVideo]]
  def findNextForUser(userId: Option[user.Id], lastPromoVideoId: Option[video.Id]): F[Option[VideoToWatch]]
