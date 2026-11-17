package whitelabel.captal.platform.eventhandlers

import whitelabel.captal.core.application.Event
import whitelabel.captal.core.video.Event as VideoEvent
import whitelabel.captal.platform.VideoFiles
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** The playbacks closed for a page that never said it ended, marked expired. */
object PlaybackExpiryHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        ZIO.foreachDiscard(events):
          case Event.Video(VideoEvent.PlaybackExpired(playback, _, occurredAt)) =>
            VideoFiles.expire(quill, playback.asString, occurredAt).unit
          case _ =>
            ZIO.unit
end PlaybackExpiryHandler
