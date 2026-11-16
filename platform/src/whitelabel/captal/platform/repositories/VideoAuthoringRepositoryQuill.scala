package whitelabel.captal.platform.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.{
  CurrentFile,
  VideoAuthoringRepository,
  VideoInfo,
  WaitingFile
}
import whitelabel.captal.core.location.{LocationId, Slug}
import whitelabel.captal.core.video.Id
import whitelabel.captal.core.video.authoring.FileId
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{AdvertiserVideoRow, LocationRow, VideoFileRow, VideoStatus}
import zio.*

/** The videos as their authoring reads them: the file each plays — the newest confirmed — and the
  * one it waits for, unconfirmed, with the key `store` reads off its URL. A video of a status
  * `core` has no word for is no video they can change, and is left out.
  */
object VideoAuthoringRepositoryQuill:
  def apply(quill: QuillSqlite, store: AssetStore): VideoAuthoringRepository[Task] =
    import quill.*
    new VideoAuthoringRepository[Task]:
      def video(id: Id): Task[Option[VideoInfo]] = run(
        query[AdvertiserVideoRow].filter(_.id == lift(id))).flatMap:
        _.headOption.flatMap(row => VideoStatus.of(row.status).map(row -> _)) match
          case None =>
            ZIO.none
          case Some((row, status)) =>
            for
              files <- run(
                query[VideoFileRow].filter(f => f.videoId == lift(id) && f.deletedAt.isEmpty))
              slugs <-
                ZIO.foreach(row.locationId)(location =>
                  run(query[LocationRow].filter(_.id == lift(location)).map(_.slug)))
            yield
              val (confirmed, waiting) = files.partition(_.confirmedAt.isDefined)
              Some(
                VideoInfo(
                  row.id,
                  row.videoType,
                  status,
                  row.locationId.map(LocationId(_)),
                  slugs.flatMap(_.headOption).map(Slug.unsafe),
                  row.minWatchSeconds,
                  confirmed
                    .maxByOption(_.confirmedAt)
                    .map(file => CurrentFile(FileId(file.id), file.durationSeconds)),
                  waiting
                    .maxByOption(_.createdAt)
                    .map(file => WaitingFile(FileId(file.id), store.keyOf(file.url)))
                ))

      def activeAt(location: Option[LocationId]): Task[List[Id]] = run(
        query[AdvertiserVideoRow].filter(_.status == lift(VideoStatus.Active))).map(
        _.filter(_.locationId == location.map(_.asString)).map(_.id))
    end new
  end apply
end VideoAuthoringRepositoryQuill
