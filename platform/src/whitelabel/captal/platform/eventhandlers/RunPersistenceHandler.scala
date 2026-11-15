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
  * Its start is written only under the location's lock, taken by [[OperationLockHandler]] earlier
  * in the same transaction: a start that lost the lock leaves no row, so no run appears that
  * nothing will ever take.
  */
object RunPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Operation(started: OperationEvent.OperationStarted) =>
            val id = started.run.asString
            val slug = started.slug.asString
            val operation = started.operation.name
            val steps = RunRecords.pending(started.steps)
            val at = started.occurredAt.toString
            val location = started.location.asString
            quill
              .run(
                quote(
                  sql"""INSERT INTO mcp_deploy_runs
                          (id, slug, operation, status, steps, result, error, started_by,
                           started_at, heartbeat_at, finished_at)
                        SELECT ${lift(id)}, ${lift(slug)}, ${lift(operation)},
                               ${lift(RunRecords.Running)}, ${lift(steps)}, NULL, NULL,
                               ${lift(started.by)}, ${lift(at)}, ${lift(at)}, NULL
                        WHERE EXISTS (
                          SELECT 1 FROM locations
                          WHERE id = ${lift(location)} AND current_run = ${lift(id)})""".as[Insert[
                    RunRow]]))
              .unit
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
