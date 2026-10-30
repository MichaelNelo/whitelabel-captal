package whitelabel.captal.platform.eventhandlers

import cats.data.NonEmptyChain
import whitelabel.captal.core.application.{Error as AppError, Event, EventHandler, Flow}
import whitelabel.captal.core.video.{Error as VideoError, Event as VideoEvent}
import whitelabel.captal.platform.VideoFiles
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** Records playbacks starting and ending.
  *
  * Outside the transactional chain, after it commits: inside a transaction the driver defers every
  * write to the commit and reports none of them, and a playback has to know whether its insert
  * found the file still current. Not in the event log either — `video_playbacks` is the record, and
  * a start refused here would otherwise be logged as having happened.
  */
object PlaybackHandler:

  def apply(quill: QuillSqlite): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        ZIO.foreachDiscard(events):
          case Event.Video(
                VideoEvent.PlaybackStarted(playbackId, sessionId, videoId, fileUrl, occurredAt)) =>
            VideoFiles
              .start(quill, playbackId.asString, sessionId, videoId, fileUrl, occurredAt)
              .flatMap: recorded =>
                // The file was replaced between the command reading it and this insert.
                ZIO.unless(recorded)(
                  ZIO.fail(
                    Flow.HandlerError(
                      NonEmptyChain.one(
                        AppError.Video(
                          NonEmptyChain.one(VideoError.StaleFile(videoId, fileUrl)))))))
              .unit
          case Event.Video(VideoEvent.PlaybackEnded(playbackId, sessionId, occurredAt)) =>
            VideoFiles.end(quill, playbackId.asString, sessionId, occurredAt).unit
          case _ =>
            ZIO.unit
end PlaybackHandler
