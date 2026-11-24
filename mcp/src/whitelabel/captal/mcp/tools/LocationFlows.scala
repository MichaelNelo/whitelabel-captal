package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.commands.{
  CreateLocationCommand,
  CreateLocationHandler,
  Handler,
  RegisterAccessPointCommand,
  RegisterAccessPointHandler,
  RemoveAccessPointCommand,
  RemoveAccessPointHandler,
  RequireFingerprintCommand,
  RequireFingerprintHandler,
  UpdateAccessPointCommand,
  UpdateAccessPointHandler
}
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.queries.DispatchQuery
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{AccessPointRepository, LocationRepository}
import whitelabel.captal.core.location.{AccessPoint, Location, State}
import whitelabel.captal.mcp.aws.DispatchRules
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.eventhandlers.{
  EventLogHandler,
  LocationPersistenceHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** The location's commands as flows, composed once, as the API's are: each through the chain that
  * writes what it decided and logs it under the operator a call publishes ([[OperatorContext]]).
  */
object LocationFlows:
  type Create = Flow.Aux[Task, CreateLocationCommand, Location[State.Created.type]]
  type RequireFingerprint = Flow.Aux[Task, RequireFingerprintCommand, Unit]
  type RegisterAccessPoint = Flow.Aux[Task, RegisterAccessPointCommand, AccessPoint]
  type UpdateAccessPoint = Flow.Aux[Task, UpdateAccessPointCommand, AccessPoint]
  type RemoveAccessPoint = Flow.Aux[Task, RemoveAccessPointCommand, Unit]
  type All =
    Create & RequireFingerprint & RegisterAccessPoint & UpdateAccessPoint & RemoveAccessPoint

  type Repositories = LocationRepository[Task] & AccessPointRepository[Task]

  private def flow[C, R](quill: QuillSqlite, operators: OperatorContext)(
      handler: Repositories => Handler.Aux[Task, C, R]): Flow.Aux[Task, C, R] = Flow(
    handler(LocationRepositoryQuill(quill)),
    TransactionalEventHandler(
      LocationPersistenceHandler().andThen(EventLogHandler.forOperator(operators)),
      quill)
  )

  val create: ZLayer[QuillSqlite & OperatorContext, Nothing, Create] = ZLayer.fromFunction:
    (quill: QuillSqlite, operators: OperatorContext) =>
      flow(quill, operators)(CreateLocationHandler[Task](_))

  val requireFingerprint: ZLayer[QuillSqlite & OperatorContext, Nothing, RequireFingerprint] =
    ZLayer.fromFunction: (quill: QuillSqlite, operators: OperatorContext) =>
      flow(quill, operators)(RequireFingerprintHandler[Task](_))

  /** `flow`, and once it committed, the dispatch rules of the MACs the command touched written
    * again: where a MAC's guests go depends on the access points its locations have.
    */
  private def dispatching[C, R](dispatch: DispatchRules)(macs: C => Set[Mac])(
      flow: Flow.Aux[Task, C, R]): Flow.Aux[Task, C, R] =
    new Flow[Task, C]:
      type Result = R
      def execute(command: C): Task[R] = flow
        .execute(command)
        .tap(_ => dispatch.write(DispatchQuery.Of(macs(command))))

  private def register(quill: QuillSqlite, operators: OperatorContext, dispatch: DispatchRules) =
    dispatching(dispatch)((c: RegisterAccessPointCommand) => Set(c.mac))(
      flow(quill, operators)(repositories =>
        RegisterAccessPointHandler[Task](repositories, repositories)))

  private def update(quill: QuillSqlite, operators: OperatorContext, dispatch: DispatchRules) =
    dispatching(dispatch)((c: UpdateAccessPointCommand) => Set(c.mac) ++ c.newMac)(
      flow(quill, operators)(repositories =>
        UpdateAccessPointHandler[Task](repositories, repositories)))

  private def remove(quill: QuillSqlite, operators: OperatorContext, dispatch: DispatchRules) =
    dispatching(dispatch)((c: RemoveAccessPointCommand) => Set(c.mac))(
      flow(quill, operators)(repositories =>
        RemoveAccessPointHandler[Task](repositories, repositories)))

  val registerAccessPoint
      : ZLayer[QuillSqlite & OperatorContext & DispatchRules, Nothing, RegisterAccessPoint] = ZLayer
    .fromFunction(register)

  val updateAccessPoint
      : ZLayer[QuillSqlite & OperatorContext & DispatchRules, Nothing, UpdateAccessPoint] = ZLayer
    .fromFunction(update)

  val removeAccessPoint
      : ZLayer[QuillSqlite & OperatorContext & DispatchRules, Nothing, RemoveAccessPoint] = ZLayer
    .fromFunction(remove)

  /** The five, for a caller that builds its environment by hand — a test that runs the tools
    * without `Main`'s layers — writing the dispatch rules through `dispatch`, none by default.
    * Added one by one, so each is found by its own type.
    */
  def environment(
      quill: QuillSqlite,
      operators: OperatorContext,
      dispatch: DispatchRules = DispatchRules.off): ZEnvironment[All] = ZEnvironment
    .empty
    .add[Create](flow(quill, operators)(CreateLocationHandler[Task](_)))
    .add[RequireFingerprint](flow(quill, operators)(RequireFingerprintHandler[Task](_)))
    .add[RegisterAccessPoint](register(quill, operators, dispatch))
    .add[UpdateAccessPoint](update(quill, operators, dispatch))
    .add[RemoveAccessPoint](remove(quill, operators, dispatch))
end LocationFlows
