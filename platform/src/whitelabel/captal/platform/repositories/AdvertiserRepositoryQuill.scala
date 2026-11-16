package whitelabel.captal.platform.repositories

import io.getquill.*
import whitelabel.captal.core.advertiser.Advertiser
import whitelabel.captal.core.infrastructure.AdvertiserRepository
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.platform.AdvertiserRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** The advertisers as `advertisers` holds them. A row whose id is not a UUID is no advertiser
  * `core` can name, and is left out.
  */
object AdvertiserRepositoryQuill:
  def apply(quill: QuillSqlite): AdvertiserRepository[Task] =
    import quill.*
    new AdvertiserRepository[Task]:
      def find(id: AdvertiserId): Task[Option[Advertiser]] = run(
        query[AdvertiserRow].filter(_.id == lift(id.asString))).map(
        _.flatMap(advertiserOf).headOption)

      def named(name: String): Task[List[Advertiser]] = run(
        query[AdvertiserRow].filter(_.name == lift(name))).map(_.flatMap(advertiserOf))

  def advertiserOf(row: AdvertiserRow): Option[Advertiser] = AdvertiserId
    .fromString(row.id)
    .map(Advertiser(_, row.name, row.priority, row.isActive == 1))
end AdvertiserRepositoryQuill
