package whitelabel.captal.platform.queries

import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{AdvertiserReport, ListAdvertisersQuery, Query}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{AdvertiserRow, AdvertiserVideoRow, VideoStatus}
import zio.*

/** [[ListAdvertisersQuery]], read from `advertisers`, each with how many of its videos are in
  * rotation, sorted by name.
  */
object ListAdvertisers:
  def apply(quill: QuillSqlite): Query.Aux[Task, ListAdvertisersQuery, List[AdvertiserReport]] =
    new Query[Task, ListAdvertisersQuery]:
      type Result = List[AdvertiserReport]

      def execute(q: ListAdvertisersQuery): Task[Either[Error, List[AdvertiserReport]]] =
        import quill.*
        for
          rows <- run(query[AdvertiserRow])
          visible = rows.filter(row => q.includeInactive || row.isActive == 1).sortBy(_.name)
          reports <-
            ZIO.foreach(visible): row =>
              activeVideos(quill, row.id).map: videos =>
                AdvertiserReport(row.id, row.name, row.priority, row.isActive == 1, videos)
        yield Right(reports)

  /** How many of the advertiser `id`'s videos are in rotation. */
  def activeVideos(quill: QuillSqlite, id: String): Task[Int] =
    import quill.*
    run(
      query[AdvertiserVideoRow]
        .filter(v => v.advertiserId.contains(lift(id)) && v.status == lift(VideoStatus.Active))
        .size).map(_.toInt)
end ListAdvertisers
