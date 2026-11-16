package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  PendingUploadReport,
  PendingUploadsQuery,
  Query,
  UploadUrlQuery,
  VideoReport,
  VideosOfQuery,
  WaitingUpload
}
import whitelabel.captal.core.survey.question.LocalizedText
import whitelabel.captal.core.video
import whitelabel.captal.core.video.authoring.{
  Error as VideoError,
  FileId,
  VideoRules,
  VideoStatus as Status
}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AdvertiserRow,
  AdvertiserVideoRow,
  BackendTexts,
  LocalizedTextRow,
  LocationRow,
  VideoFileRow,
  VideoStatus
}
import zio.*

/** [[VideosOfQuery]], [[PendingUploadsQuery]] and [[UploadUrlQuery]], read from the videos, their
  * files and their texts. A video's title is the one [[VideoRules.titleOf]] picks of its texts; a
  * video of a status `core` has no word for is left out.
  */
object Videos:
  def of(quill: QuillSqlite): Query.Aux[Task, VideosOfQuery, List[VideoReport]] =
    new Query[Task, VideosOfQuery]:
      type Result = List[VideoReport]

      def execute(q: VideosOfQuery): Task[Either[Error, List[VideoReport]]] =
        import quill.*
        for
          videos      <- run(query[AdvertiserVideoRow])
          files       <- run(query[VideoFileRow].filter(_.deletedAt.isEmpty))
          locations   <- run(query[LocationRow])
          advertisers <- run(query[AdvertiserRow])
          titles      <- titlesOf(quill)
        yield
          val slugOf = locations.map(l => l.id -> l.slug).toMap
          val nameOf = advertisers.map(a => a.id -> a.name).toMap
          val filesOf = files.groupBy(_.videoId)
          Right(
            videos
              .flatMap: row =>
                VideoStatus
                  .of(row.status)
                  .map: status =>
                    val (confirmed, waiting) = filesOf
                      .getOrElse(row.id, Nil)
                      .partition(_.confirmedAt.isDefined)
                    val current = confirmed.maxByOption(_.confirmedAt)
                    VideoReport(
                      id = row.id.asString,
                      title = titles.get(row.id.asString),
                      slug = row.locationId.flatMap(slugOf.get),
                      videoType = row.videoType,
                      advertiser = row.advertiserId.flatMap(nameOf.get),
                      status = status,
                      durationSeconds = current.flatMap(_.durationSeconds),
                      minWatchSeconds = row.minWatchSeconds,
                      priority = row.priority,
                      replacing = current.isDefined && waiting.nonEmpty
                    )
              .filter(v => q.slug.forall(slug => v.slug.contains(slug.asString)))
              .filter(v => q.includeRetired || v.status != Status.Retired)
              // In rotation first, since that is what guests see; by descending priority within
              // a status, the order the rotation draws in.
              .sortBy(v => (rank(v.status), -v.priority, v.title.getOrElse(v.id))))
        end for
      end execute

  def pending(quill: QuillSqlite): Query.Aux[Task, PendingUploadsQuery, List[PendingUploadReport]] =
    new Query[Task, PendingUploadsQuery]:
      type Result = List[PendingUploadReport]

      def execute(q: PendingUploadsQuery): Task[Either[Error, List[PendingUploadReport]]] =
        import quill.*
        for
          videos    <- run(query[AdvertiserVideoRow].filter(_.status == lift(VideoStatus.Pending)))
          locations <- run(query[LocationRow])
          titles    <- titlesOf(quill)
        yield
          val slugOf = locations.map(l => l.id -> l.slug).toMap
          Right(
            videos
              .map(row =>
                PendingUploadReport(
                  row.id.asString,
                  titles.get(row.id.asString),
                  row.locationId.flatMap(slugOf.get),
                  row.createdAt))
              .filter(p => q.slug.forall(slug => p.slug.contains(slug.asString)))
              // Oldest first: the likeliest to be abandoned.
              .sortBy(_.createdAt))
        end for
      end execute

  def uploadUrl(
      quill: QuillSqlite,
      store: AssetStore): Query.Aux[Task, UploadUrlQuery, WaitingUpload] =
    new Query[Task, UploadUrlQuery]:
      type Result = WaitingUpload

      def execute(q: UploadUrlQuery): Task[Either[Error, WaitingUpload]] =
        import quill.*
        def refused(error: VideoError) = ZIO.left(Error.VideoAuthoring(NonEmptyChain.one(error)))
        video.Id.fromString(q.video.trim) match
          case None =>
            refused(VideoError.VideoNotFound(q.video))
          case Some(id) =>
            run(query[AdvertiserVideoRow].filter(_.id == lift(id))).flatMap:
              _.headOption match
                case None =>
                  refused(VideoError.VideoNotFound(q.video))
                case Some(row) =>
                  for
                    files <- run(
                      query[VideoFileRow].filter(f =>
                        f.videoId == lift(id) && f.confirmedAt.isEmpty && f.deletedAt.isEmpty))
                    slugs <-
                      ZIO.foreach(row.locationId)(location =>
                        run(query[LocationRow].filter(_.id == lift(location)).map(_.slug)))
                    texts <- BackendTexts.of(quill, id.asString)
                    waiting = files.maxByOption(_.createdAt)
                  yield waiting
                    .map(file =>
                      WaitingUpload(
                        id.asString,
                        titleOf(texts),
                        slugs.flatMap(_.headOption),
                        FileId(file.id),
                        store.keyOf(file.url),
                        file.url))
                    .toRight(Error.VideoAuthoring(NonEmptyChain.one(VideoError.NoFileWaiting(id))))
        end match
      end execute

  private def rank(status: Status): Int =
    status match
      case Status.Active =>
        0
      case Status.Pending =>
        1
      case Status.Retired =>
        2

  /** Every entity's title of its backend texts, by its id. */
  private def titlesOf(quill: QuillSqlite): Task[Map[String, String]] =
    import quill.*
    run(
      query[LocalizedTextRow].filter(t =>
        t.category == lift(BackendTexts.Category) && t.isActive == 1)).map(
      _.groupBy(_.entityId)
        .view
        .mapValues(rows => VideoRules.titleOf(rows.map(r => LocalizedText(r.value, r.locale))))
        .toMap)

  private def titleOf(texts: List[LocalizedText]): Option[String] =
    Option.when(texts.nonEmpty)(VideoRules.titleOf(texts))
end Videos
