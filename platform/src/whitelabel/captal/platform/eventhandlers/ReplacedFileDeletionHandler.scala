package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.video.authoring.Event as VideoEvent
import whitelabel.captal.platform.VideoFileRow
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** After the commit, the objects of the files it marked deleted — only those the commit did mark,
  * read back: the mark is the one statement that checks nobody plays the file, and an object goes
  * only on its say. An object that cannot be deleted is only logged: the file is marked, so no
  * guest is given it again, and what is left is storage, not a fault anyone would see.
  */
object ReplacedFileDeletionHandler:
  def apply(quill: QuillSqlite, store: AssetStore): EventHandler[Task, Event] =
    import quill.*
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        ZIO.foreachDiscard(events):
          case Event.VideoAuthoring(VideoEvent.FileDeleted(_, file, key, _)) =>
            run(query[VideoFileRow].filter(_.id == lift(file.asString)).map(_.deletedAt)).flatMap:
              marked =>
                ZIO.when(marked.exists(_.isDefined))(
                  store
                    .deleteIfPresent(key)
                    .tapError(why =>
                      ZIO.logWarning(
                        s"Video file ${file
                            .asString} is marked deleted but its object is still there: $why"))
                    .ignore)
          case _ =>
            ZIO.unit
end ReplacedFileDeletionHandler
