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
  * Starting takes the location's lock in Redis for the run, then writes the run in one transaction
  * and queues it for [[OperationRunner]] — data only, so no handler here runs a flow — keeping the
  * lock for the runner; a start that fails lets it go. Ending writes the location's new state and
  * closes the run in one transaction; the runner lets the lock go after it.
  */
object OperationFlows:
  type Start = Flow.Aux[Task, StartOperationCommand, Unit]
  type Complete = Flow.Aux[Task, CompleteOperationCommand, Unit]

  def start(
      quill: QuillSqlite,
      operators: OperatorContext,
      queue: OperationQueue,
      locks: DistributedLock): Start =
    val locations = LocationRepositoryQuill(quill)
    Flow(
      StartOperationHandler[Task](
        locations,
        locations,
        RunRepositoryQuill(quill, locks),
        TermsRepositoryQuill(quill)),
      OperationLock.taking(locks)(
        TransactionalEventHandler(
          RunPersistenceHandler().andThen(EventLogHandler.forOperator(operators)),
          quill).andThen(OperationQueueHandler(queue)))
    )

  def complete(quill: QuillSqlite, operators: OperatorContext, locks: DistributedLock): Complete =
    Flow(
      CompleteOperationHandler[Task](RunRepositoryQuill(quill, locks)),
      TransactionalEventHandler(
        LocationPersistenceHandler()
          .andThen(RunPersistenceHandler())
          .andThen(EventLogHandler.forOperator(operators)),
        quill)
    )

  val startLayer
      : ZLayer[QuillSqlite & OperatorContext & OperationQueue & DistributedLock, Nothing, Start] =
    ZLayer.fromFunction(start)

  val completeLayer: ZLayer[QuillSqlite & OperatorContext & DistributedLock, Nothing, Complete] =
    ZLayer.fromFunction(complete)
end OperationFlows
