package whitelabel.captal.infra

import io.getquill.NamingStrategy
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.jdbczio.Quill
import whitelabel.captal.core.application.{Event, EventHandler}
import zio.*

object TransactionalEventHandler:
  def apply[D <: SqlIdiom, N <: NamingStrategy](
      dbHandler: DbEventHandler[D, N, Event],
      quill: Quill[D, N]
  ): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        quill
          .transaction:
            dbHandler.handle(events, quill)
          .tapError: error =>
            ZIO.logError(s"Event handling failed, transaction rolled back: ${error.getMessage}")
