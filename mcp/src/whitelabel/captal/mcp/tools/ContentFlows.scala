package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.commands.{
  ChangeRecoveryPolicyCommand,
  ChangeRecoveryPolicyHandler,
  PublishTermsVersionCommand,
  PublishTermsVersionHandler,
  RemoveTermsTranslationCommand,
  RemoveTermsTranslationHandler,
  SetTermsTranslationCommand,
  SetTermsTranslationHandler
}
import whitelabel.captal.core.application.{Event, Flow}
import whitelabel.captal.core.recovery.PolicyOverrides
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.eventhandlers.{
  DbEventHandler,
  EventLogHandler,
  RecoveryHandler,
  TermsPersistenceHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.repositories.{
  LocationRepositoryQuill,
  RecoveryPolicyRepositoryQuill,
  TermsRepositoryQuill
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
  type PublishTermsVersion = Flow.Aux[Task, PublishTermsVersionCommand, Int]
  type SetTermsTranslation = Flow.Aux[Task, SetTermsTranslationCommand, Unit]
  type RemoveTermsTranslation = Flow.Aux[Task, RemoveTermsTranslationCommand, Unit]
  type All =
    ChangeRecoveryPolicy & PublishTermsVersion & SetTermsTranslation & RemoveTermsTranslation

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

  def publishTermsVersion(quill: QuillSqlite, operators: OperatorContext): PublishTermsVersion =
    Flow(
      PublishTermsVersionHandler[Task](
        TermsRepositoryQuill(quill),
        TermsRepositoryQuill.texts(quill)),
      logged(quill, operators)(TermsPersistenceHandler()))

  def setTermsTranslation(quill: QuillSqlite, operators: OperatorContext): SetTermsTranslation =
    Flow(
      SetTermsTranslationHandler[Task](TermsRepositoryQuill(quill)),
      logged(quill, operators)(TermsPersistenceHandler()))

  def removeTermsTranslation(
      quill: QuillSqlite,
      operators: OperatorContext): RemoveTermsTranslation = Flow(
    RemoveTermsTranslationHandler[Task](
      TermsRepositoryQuill(quill),
      TermsRepositoryQuill.texts(quill)),
    logged(quill, operators)(TermsPersistenceHandler())
  )

  private type Needs = QuillSqlite & OperatorContext

  val changeRecoveryPolicyLayer: ZLayer[Needs, Nothing, ChangeRecoveryPolicy] = ZLayer.fromFunction(
    changeRecoveryPolicy)

  val publishTermsVersionLayer: ZLayer[Needs, Nothing, PublishTermsVersion] = ZLayer.fromFunction(
    publishTermsVersion)

  val setTermsTranslationLayer: ZLayer[Needs, Nothing, SetTermsTranslation] = ZLayer.fromFunction(
    setTermsTranslation)

  val removeTermsTranslationLayer: ZLayer[Needs, Nothing, RemoveTermsTranslation] = ZLayer
    .fromFunction(removeTermsTranslation)

  /** Every one, for a caller that builds its environment by hand — a test that runs the tools
    * without `Main`'s layers. Added one by one, so each is found by its own type.
    */
  def environment(quill: QuillSqlite, operators: OperatorContext): ZEnvironment[All] = ZEnvironment
    .empty
    .add[ChangeRecoveryPolicy](changeRecoveryPolicy(quill, operators))
    .add[PublishTermsVersion](publishTermsVersion(quill, operators))
    .add[SetTermsTranslation](setTermsTranslation(quill, operators))
    .add[RemoveTermsTranslation](removeTermsTranslation(quill, operators))
end ContentFlows
