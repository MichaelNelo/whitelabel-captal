package whitelabel.captal.infra.eventhandlers

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

object TransactionalEventHandler:
  def apply(dbHandler: DbEventHandler[Event], quill: QuillSqlite): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] = quill
        .transaction:
          dbHandler.handle(events, quill)
        .tapError: error =>
          ZIO.logError(s"Event handling failed, transaction rolled back: ${error.getMessage}")
