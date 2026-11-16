package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.advertiser.Advertiser
import whitelabel.captal.core.survey.AdvertiserId

trait AdvertiserRepository[F[_]]:
  def find(id: AdvertiserId): F[Option[Advertiser]]

  /** The advertisers called `name`, retired ones included. */
  def named(name: String): F[List[Advertiser]]
