package whitelabel.captal.platform.operations

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.operation.{Event as OperationEvent, RunId}
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

/** After the commit: the run that started goes to the runner, its location's lock taken for it
  * ([[OperationLock.taking]]).
  */
object OperationQueueHandler:
  def apply(queue: OperationQueue): EventHandler[Task, Event] =
    events =>
      ZIO.foreachDiscard(
        events.collect { case Event.Operation(started: OperationEvent.OperationStarted) =>
          started.run
        })(queue.enqueue)
end OperationQueueHandler
