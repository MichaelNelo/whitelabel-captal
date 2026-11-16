package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.operation.Event as OperationEvent
import whitelabel.captal.platform.operations.RunRecords
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.RunRow
import zio.*

/** A run's row in `mcp_deploy_runs`: written when it starts, with every step pending, and closed
  * when it ends. What happens in between — each step, the heartbeat — its runner writes as it goes.
  *
  * Its start is written with the location's lock already taken for it
  * ([[whitelabel.captal.platform.operations.OperationLock.taking]]): a start that did not get the
  * lock writes nothing.
  */
object RunPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Operation(started: OperationEvent.OperationStarted) =>
            val at = started.occurredAt.toString
            val row = RunRow(
              id = started.run.asString,
              slug = started.slug.asString,
              operation = started.operation.name,
              status = RunRecords.Running,
              steps = RunRecords.pending(started.steps),
              result = None,
              error = None,
              startedBy = started.by,
              startedAt = at,
              heartbeatAt = at,
              finishedAt = None
            )
            quill.run(query[RunRow].insertValue(lift(row))).unit
          case Event.Operation(OperationEvent.OperationCompleted(run, _, occurredAt)) =>
            val at = occurredAt.toString
            quill
              .run(
                query[RunRow]
                  .filter(_.id == lift(run.asString))
                  .update(
                    _.status      -> lift(RunRecords.Succeeded),
                    _.heartbeatAt -> lift(at),
                    _.finishedAt  -> lift(Option(at))))
              .unit
          case Event.Operation(OperationEvent.OperationFailed(run, _, _, failure, occurredAt)) =>
            val at = occurredAt.toString
            quill
              .run(
                query[RunRow]
                  .filter(_.id == lift(run.asString))
                  .update(
                    _.status      -> lift(RunRecords.Failed),
                    _.error       -> lift(Option(failure.detail)),
                    _.heartbeatAt -> lift(at),
                    _.finishedAt  -> lift(Option(at))))
              .unit
          case _ =>
            ZIO.unit
      end handle
end RunPersistenceHandler
