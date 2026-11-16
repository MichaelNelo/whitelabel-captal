package whitelabel.captal.mcp.tools

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.McpToolContext
import whitelabel.captal.core.application.queries.Query
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*

/** Where a tool meets `core`: it runs a command's flow as the calling operator, published for its
  * handlers, or a query; and turns a refusal into what the operator reads, each tool describing its
  * own domain's errors (`describe`). An error no tool describes is shown as it is.
  */
object Commands:
  def run[C, R](operator: Operator, command: C)(flow: Flow.Aux[Task, C, R])(
      describe: PartialFunction[AppError, List[String]]): ZIO[OperatorContext, String, R] = ZIO
    .serviceWithZIO[OperatorContext](_.locally(operator)(flow.execute(command)))
    .catchAll:
      case Flow.HandlerError(errors) =>
        ZIO.fail(described(errors.toList, describe))
      case defect =>
        ZIO.die(defect)

  /** The query's answer, or what the operator reads of its refusal. */
  def ask[Q, R](query: Query.Aux[Task, Q, R], q: Q)(
      describe: PartialFunction[AppError, List[String]]): IO[String, R] = query
    .execute(q)
    .orDie
    .flatMap(ZIO.fromEither(_).mapError(error => described(List(error), describe)))

  /** The operator token the call carries, which a change is recorded under. */
  def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("This call carries no operator token to record the change under.")

  private def described(
      errors: List[AppError],
      describe: PartialFunction[AppError, List[String]]): String = errors
    .flatMap(error => describe.applyOrElse(error, other => List(other.toString)))
    .mkString(" ")
end Commands
