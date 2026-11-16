package whitelabel.captal.mcp.tools

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.McpToolContext
import whitelabel.captal.core.application.queries.Query
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*

/** Where the backoffice meets `core`: it runs a command's flow as an operator, published for its
  * handlers, or a query; and turns a refusal into what the operator reads, [[Refusals.describe]]
  * unless `describe` says otherwise. The tools reach it through [[Adapters]].
  */
object Commands:
  def run[C, R](operator: Operator, command: C)(flow: Flow.Aux[Task, C, R])(
      describe: AppError => String = Refusals.describe): ZIO[OperatorContext, String, R] = ZIO
    .serviceWithZIO[OperatorContext](_.locally(operator)(flow.execute(command)))
    .catchAll:
      case Flow.HandlerError(errors) =>
        ZIO.fail(errors.toList.map(describe).mkString(" "))
      case defect =>
        ZIO.die(defect)

  /** As [[run]], as the operator whose token the call `ctx` carries. */
  def runAs[C, R](ctx: McpToolContext, command: C)(flow: Flow.Aux[Task, C, R])(
      describe: AppError => String = Refusals.describe): ZIO[OperatorContext, String, R] = callerOf(
    ctx).flatMap(caller => run(Operator(caller, None), command)(flow)(describe))

  /** The query's answer, or what the operator reads of its refusal. */
  def ask[Q, R](query: Query.Aux[Task, Q, R], q: Q)(
      describe: AppError => String = Refusals.describe): IO[String, R] = query
    .execute(q)
    .orDie
    .flatMap(ZIO.fromEither(_).mapError(describe))

  /** The operator token the call carries, which a change is recorded under. */
  def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("This call carries no operator token to record the change under.")
end Commands
