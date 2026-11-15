package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.{
  CompleteOperationCommand,
  CompleteOperationHandler,
  StartOperationCommand,
  StartOperationHandler
}
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.eventhandlers.{
  EventLogHandler,
  LocationPersistenceHandler,
  OperationLockHandler,
  RunPersistenceHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.locks.DistributedLock
import whitelabel.captal.platform.operations.{OperationLock, OperationQueue, OperationQueueHandler}
import whitelabel.captal.platform.repositories.{
  LocationRepositoryQuill,
  RunRepositoryQuill,
  TermsRepositoryQuill
}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** A location's operations as flows, composed once: one starts a run, the other ends it.
  *
  * Starting takes the lock and writes the run under it in one transaction, and after the commit
  * learns whether the lock was its own and queues the run for [[OperationRunner]] — data only, so
  * no handler here runs a flow. Ending writes the location's new state, closes the run and releases
  * the lock, in one transaction.
  */
object OperationFlows:
  type Start = Flow.Aux[Task, StartOperationCommand, Unit]
  type Complete = Flow.Aux[Task, CompleteOperationCommand, Unit]

  def start(quill: QuillSqlite, operators: OperatorContext, queue: OperationQueue): Start =
    val locations = LocationRepositoryQuill(quill)
    Flow(
      StartOperationHandler[Task](
        locations,
        locations,
        RunRepositoryQuill(quill),
        TermsRepositoryQuill(quill)),
      TransactionalEventHandler(
        OperationLockHandler()
          .andThen(RunPersistenceHandler())
          .andThen(EventLogHandler.forOperator(operators)),
        quill).andThen(OperationQueueHandler(quill, queue))
    )

  /** After the commit, the location's lock is let go: the run's end is written first, so the next
    * operation to take the lock finds it ended.
    */
  def complete(quill: QuillSqlite, operators: OperatorContext, locks: DistributedLock): Complete =
    Flow(
      CompleteOperationHandler[Task](RunRepositoryQuill(quill)),
      TransactionalEventHandler(
        LocationPersistenceHandler()
          .andThen(RunPersistenceHandler())
          .andThen(OperationLockHandler())
          .andThen(EventLogHandler.forOperator(operators)),
        quill).andThen(OperationLock.releasing(locks))
    )

  val startLayer: ZLayer[QuillSqlite & OperatorContext & OperationQueue, Nothing, Start] = ZLayer
    .fromFunction(start)

  val completeLayer: ZLayer[QuillSqlite & OperatorContext & DistributedLock, Nothing, Complete] =
    ZLayer.fromFunction(complete)
end OperationFlows
