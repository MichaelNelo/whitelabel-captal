package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.operation.Event as OperationEvent
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** The location's lock, `locations.current_run`: taken when a run starts, released when it ends.
  *
  * Taken by a conditional write — the new run where the column still holds what the start
  * supersedes, nothing or a run whose runner died — which the leader serializes, so of two starts
  * that both found it free one lands. Inside the transaction rqlite reports no row count, so which
  * one did is read after the commit ([[OperationQueueHandler]]); the run's row is written only
  * under a lock that is its own ([[RunPersistenceHandler]]), so the loser leaves nothing behind.
  */
object OperationLockHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Operation(started: OperationEvent.OperationStarted) =>
            val location = started.location.asString
            val taker = Option(started.run.asString)
            started.supersedes match
              case None =>
                quill
                  .run(
                    query[LocationRow]
                      .filter(l => l.id == lift(location) && l.currentRun.isEmpty)
                      .update(_.currentRun -> lift(taker)))
                  .unit
              case Some(held) =>
                quill
                  .run(
                    query[LocationRow]
                      .filter(l =>
                        l.id == lift(location) && l.currentRun.contains(lift(held.asString)))
                      .update(_.currentRun -> lift(taker)))
                  .unit
          case Event.Operation(OperationEvent.OperationCompleted(ended, location, _)) =>
            release(quill, location.asString, ended.asString)
          case Event.Operation(OperationEvent.OperationFailed(ended, location, _, _, _)) =>
            release(quill, location.asString, ended.asString)
          case _ =>
            ZIO.unit
      end handle

  /** Only by the run that holds it, so a late completion cannot free another run's lock. */
  private def release(quill: QuillSqlite, location: String, holder: String): Task[Unit] =
    import quill.*
    quill
      .run(
        query[LocationRow]
          .filter(l => l.id == lift(location) && l.currentRun.contains(lift(holder)))
          .update(_.currentRun -> lift(Option.empty[String])))
      .unit
end OperationLockHandler
