package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.commands.{
  ChangeRecoveryPolicyCommand,
  ChangeRecoveryPolicyHandler
}
import whitelabel.captal.core.application.{Event, Flow}
import whitelabel.captal.core.recovery.PolicyOverrides
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.eventhandlers.{
  DbEventHandler,
  EventLogHandler,
  RecoveryHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.repositories.{
  LocationRepositoryQuill,
  RecoveryPolicyRepositoryQuill
}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** The content's commands as flows, composed once, as [[LocationFlows]] composes the location's:
  * each through the chain that writes what it decided and logs it under the operator a call
  * publishes ([[OperatorContext]]).
  */
object ContentFlows:
  type ChangeRecoveryPolicy = Flow.Aux[Task, ChangeRecoveryPolicyCommand, PolicyOverrides]
  type All = ChangeRecoveryPolicy

  /** What `writes` persists, then the event log, in one transaction. */
  private def logged(quill: QuillSqlite, operators: OperatorContext)(
      writes: DbEventHandler[Event]) = TransactionalEventHandler(
    writes.andThen(EventLogHandler.forOperator(operators)),
    quill)

  def changeRecoveryPolicy(quill: QuillSqlite, operators: OperatorContext): ChangeRecoveryPolicy =
    Flow(
      ChangeRecoveryPolicyHandler[Task](
        LocationRepositoryQuill(quill),
        RecoveryPolicyRepositoryQuill(quill)),
      logged(quill, operators)(RecoveryHandler()))

  val changeRecoveryPolicyLayer
      : ZLayer[QuillSqlite & OperatorContext, Nothing, ChangeRecoveryPolicy] = ZLayer.fromFunction(
    changeRecoveryPolicy)

  /** Every one, for a caller that builds its environment by hand — a test that runs the tools
    * without `Main`'s layers. Added one by one, so each is found by its own type.
    */
  def environment(quill: QuillSqlite, operators: OperatorContext): ZEnvironment[All] = ZEnvironment
    .empty
    .add[ChangeRecoveryPolicy](changeRecoveryPolicy(quill, operators))
end ContentFlows
