package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.{
  ChangeIconCommand,
  ChangeIconHandler,
  ChangeStylesCommand,
  ChangeStylesHandler
}
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.eventhandlers.{
  AssetPublishingHandler,
  EventLogHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** A location's stylesheet and icon as flows, composed once: each logged under the operator a call
  * publishes, then, after the commit, published to the assets bucket and the edge invalidated.
  */
object AssetFlows:
  type ChangeStyles = Flow.Aux[Task, ChangeStylesCommand, Unit]
  type ChangeIcon = Flow.Aux[Task, ChangeIconCommand, Unit]
  type All = ChangeStyles & ChangeIcon

  private def published(quill: QuillSqlite, operators: OperatorContext, store: AssetStore) =
    TransactionalEventHandler(EventLogHandler.forOperator(operators), quill).andThen(
      AssetPublishingHandler(store))

  def changeStyles(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): ChangeStyles = Flow(
    ChangeStylesHandler[Task](LocationRepositoryQuill(quill)),
    published(quill, operators, store))

  def changeIcon(quill: QuillSqlite, operators: OperatorContext, store: AssetStore): ChangeIcon =
    Flow(
      ChangeIconHandler[Task](LocationRepositoryQuill(quill)),
      published(quill, operators, store))

  private type Needs = QuillSqlite & OperatorContext & AssetStore

  val changeStylesLayer: ZLayer[Needs, Nothing, ChangeStyles] = ZLayer.fromFunction(changeStyles)

  val changeIconLayer: ZLayer[Needs, Nothing, ChangeIcon] = ZLayer.fromFunction(changeIcon)

  /** Both, for a caller that builds its environment by hand. */
  def environment(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): ZEnvironment[All] = ZEnvironment
    .empty
    .add[ChangeStyles](changeStyles(quill, operators, store))
    .add[ChangeIcon](changeIcon(quill, operators, store))
end AssetFlows
