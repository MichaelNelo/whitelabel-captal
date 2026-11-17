package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.video.Id
import whitelabel.captal.core.video.authoring.{Event as VideoEvent, FileId, VideoKind}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AdvertiserVideoRow,
  BackendTexts,
  VideoFileRow,
  VideoFiles,
  VideoStatus
}
import zio.*

/** The videos and their files, as the backoffice's commands decided them. A file's row holds the
  * URL the portal fetches it from, which `store` makes of its key; a video's title and description
  * are its backend texts, the description under its id with `_desc`.
  */
object VideoPersistenceHandler:
  def apply(store: AssetStore): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        def reserved(video: Id, file: FileId, key: String, at: String) = run(
          query[VideoFileRow].insertValue(
            lift(VideoFileRow(file.asString, video, store.publicUrl(key), None, at, None, None))))
        def status(video: Id, word: String, at: String) = run(
          query[AdvertiserVideoRow]
            .filter(_.id == lift(video))
            .update(_.status -> lift(word), _.updatedAt -> lift(at)))
        ZIO.foreachDiscard(events):
          case Event.VideoAuthoring(
                VideoEvent.VideoRegistered(
                  id,
                  location,
                  kind,
                  title,
                  description,
                  minWatchSeconds,
                  priority,
                  file,
                  key,
                  occurredAt)) =>
            val at = occurredAt.toString
            val (advertiser, noRepeat, campaign) =
              kind match
                case VideoKind.Advertiser(advertiser, campaign) =>
                  (Some(advertiser.asString), None, campaign)
                case VideoKind.Promo(noRepeat) =>
                  (None, noRepeat, None)
            val row = AdvertiserVideoRow(
              id = id,
              advertiserId = advertiser,
              videoType = kind.videoType,
              minWatchSeconds = minWatchSeconds,
              showCountdown = 1,
              noRepeatSeconds = noRepeat,
              locationId = Some(location.asString),
              status = VideoStatus.Pending,
              priority = priority,
              createdAt = at,
              updatedAt = at,
              productCampaignId = campaign
            )
            // The survey references the video, so its row goes first.
            for
              _ <- run(query[AdvertiserVideoRow].insertValue(lift(row)))
              _ <- reserved(id, file, key, at)
              _ <- BackendTexts.write(quill, id.asString, title, at)
              _ <- BackendTexts.write(quill, s"${id.asString}_desc", description, at)
            yield ()
          case Event.VideoAuthoring(VideoEvent.FileReserved(video, file, key, occurredAt)) =>
            reserved(video, file, key, occurredAt.toString)
          case Event.VideoAuthoring(
                VideoEvent.FileConfirmed(
                  video,
                  file,
                  durationSeconds,
                  minWatchSeconds,
                  standing,
                  occurredAt)) =>
            // The switch itself: from here the video plays this file. Only an unconfirmed one
            // switches. No count is checked: inside the transaction the driver defers the write to
            // the commit and reports no row affected; that this is the file the video waits for is
            // what the command read before deciding.
            for
              _ <- run(
                query[VideoFileRow]
                  .filter(f => f.id == lift(file.asString) && f.confirmedAt.isEmpty)
                  .update(
                    _.confirmedAt     -> lift(Option(VideoFiles.stamp(occurredAt))),
                    _.durationSeconds -> lift(Option(durationSeconds))))
              _ <- run(
                query[AdvertiserVideoRow]
                  .filter(_.id == lift(video))
                  .update(
                    _.status          -> lift(VideoStatus.stored(standing)),
                    _.minWatchSeconds -> lift(minWatchSeconds),
                    _.updatedAt       -> lift(occurredAt.toString)))
            yield ()
          case Event.VideoAuthoring(
                VideoEvent.VideoUpdated(
                  video,
                  title,
                  description,
                  minWatchSeconds,
                  priority,
                  noRepeatSeconds,
                  productCampaignId,
                  occurredAt)) =>
            val at = occurredAt.toString
            for
              _ <- ZIO.foreachDiscard(title)(BackendTexts.replace(quill, video.asString, _, at))
              _ <-
                ZIO.foreachDiscard(description)(
                  BackendTexts.replace(quill, s"${video.asString}_desc", _, at))
              rowChanged =
                minWatchSeconds.isDefined || priority.isDefined || noRepeatSeconds.isDefined ||
                  productCampaignId.isDefined
              rows <-
                if rowChanged then
                  run(query[AdvertiserVideoRow].filter(_.id == lift(video)))
                else
                  ZIO.succeed(Nil)
              _ <-
                ZIO.foreachDiscard(rows): row =>
                  run(
                    query[AdvertiserVideoRow]
                      .filter(_.id == lift(video))
                      .update(
                        _.minWatchSeconds   -> lift(minWatchSeconds.getOrElse(row.minWatchSeconds)),
                        _.priority          -> lift(priority.getOrElse(row.priority)),
                        _.noRepeatSeconds   -> lift(noRepeatSeconds.orElse(row.noRepeatSeconds)),
                        _.productCampaignId ->
                          lift(productCampaignId.getOrElse(row.productCampaignId)),
                        _.updatedAt -> lift(at)
                      ))
            yield ()
            end for
          case Event.VideoAuthoring(VideoEvent.VideoRetired(video, occurredAt)) =>
            status(video, VideoStatus.Retired, occurredAt.toString)
          case Event.VideoAuthoring(VideoEvent.VideoReinstated(video, occurredAt)) =>
            status(video, VideoStatus.Active, occurredAt.toString)
          // Marked only while it is still not current and nobody plays it, in one statement: its
          // object goes after the commit, if it was ([[ReplacedFileDeletionHandler]]).
          case Event.VideoAuthoring(VideoEvent.FileDeleted(_, file, _, occurredAt)) =>
            VideoFiles.condemn(quill, file.asString, occurredAt).unit
          case _ =>
            ZIO.unit
      end handle
end VideoPersistenceHandler
