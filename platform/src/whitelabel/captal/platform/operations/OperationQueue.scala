package whitelabel.captal.platform.operations

import io.getquill.*
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.operation.{Event as OperationEvent, RunId}
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** The runs waiting for the runner, data only. Its producer side, apart from the runner and
  * dependency-free as `JobQueue` is: the handler that queues a run is in the chain the runner's own
  * flow uses, and a handler needing the flow would make the layers cyclic.
  */
final class OperationQueue private (inbox: Queue[RunId]):
  def enqueue(run: RunId): UIO[Unit] = inbox.offer(run).unit
  def take: UIO[RunId] = inbox.take

object OperationQueue:
  /** Unbounded: at most one run per location is going at a time. */
  val layer: ULayer[OperationQueue] = ZLayer.fromZIO(
    Queue.unbounded[RunId].map(new OperationQueue(_)))

/** Another start won the location's lock: `holder` holds it, or nobody does any more by the time it
  * was read.
  */
final case class TransitionTaken(run: RunId, holder: Option[RunId])
    extends Exception(
      s"Run ${run.asString} lost the location's lock to ${holder.fold("nobody")(_.asString)}")

/** After the commit: which start the lock went to, read now since inside the transaction rqlite
  * reported no row count. The run whose it is goes to the runner; one that lost fails the call with
  * [[TransitionTaken]], having left nothing behind ([[RunPersistenceHandler]]).
  */
object OperationQueueHandler:
  def apply(quill: QuillSqlite, queue: OperationQueue): EventHandler[Task, Event] =
    events =>
      import quill.*
      ZIO.foreachDiscard(
        events.collect { case Event.Operation(started: OperationEvent.OperationStarted) =>
          started
        }): started =>
        quill
          .run(query[LocationRow].filter(_.id == lift(started.location.asString)).map(_.currentRun))
          .map(_.headOption.flatten)
          .flatMap:
            case Some(holder) if holder == started.run.asString =>
              queue.enqueue(started.run)
            case holder =>
              ZIO.fail(TransitionTaken(started.run, holder.map(RunId(_))))
end OperationQueueHandler
