package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.queries.Query
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.platform.OperatorContext
import zio.*

/** A tool that runs one command or one query says only how its input becomes the command or the
  * query, and how the result becomes its answer. The rest is the same for every one, and is here:
  * the flow or the query, found in the environment where the layers put it ([[ContentFlows]],
  * [[Queries]]); the operator a change is recorded under, the token the call carries; and what a
  * refusal reads as, [[Refusals.describe]] unless `describe` says otherwise.
  *
  * Both sides are effects. Building the command reads the clock and makes its ids, and may read
  * first — `confirm_video_upload` checks the file arrived; the answer may add what is not state — a
  * signed upload URL. Either fails with what the operator reads.
  *
  * The result's type is not in the command's: the answer names it on its parameter, which is how
  * the flow is found.
  */
object Adapters:
  extension (tool: McpTool)
    /** The input becomes the command, the flow runs it as the calling operator, the result becomes
      * the answer.
      */
    def runs[In: McpInput, C: Tag, Res: Tag, Out: McpOutput, R1, R2](
        command: In => ZIO[R1, String, C],
        describe: AppError => String = Refusals.describe)(answer: (In, Res) => ZIO[R2, String, Out])
        : McpToolHandlerR[R1 & R2 & OperatorContext & Flow.Aux[Task, C, Res]] = tool
      .handleWithContext[R1 & R2 & OperatorContext & Flow.Aux[Task, C, Res], String, In, Out]:
        (in, ctx) =>
          for
            built  <- command(in)
            result <- ZIO.serviceWithZIO[Flow.Aux[Task, C, Res]](
              Commands.runAs(ctx, built)(_)(describe))
            out <- answer(in, result)
          yield out

    /** As [[runs]], for a tool whose input picks one of two commands — a retirement or a
      * reinstatement — answered alike.
      */
    def runsEither[In: McpInput, C1: Tag, C2: Tag, Res: Tag, Out: McpOutput, R1, R2](
        command: In => ZIO[R1, String, Either[C1, C2]],
        describe: AppError => String = Refusals.describe)(
        answer: (In, Res) => ZIO[R2, String, Out]): McpToolHandlerR[
      R1 & R2 & OperatorContext & Flow.Aux[Task, C1, Res] & Flow.Aux[Task, C2, Res]] = tool
      .handleWithContext[
        R1 & R2 & OperatorContext & Flow.Aux[Task, C1, Res] & Flow.Aux[Task, C2, Res],
        String,
        In,
        Out]: (in, ctx) =>
        for
          built  <- command(in)
          result <-
            built match
              case Left(first) =>
                ZIO.serviceWithZIO[Flow.Aux[Task, C1, Res]](Commands.runAs(ctx, first)(_)(describe))
              case Right(second) =>
                ZIO.serviceWithZIO[Flow.Aux[Task, C2, Res]](
                  Commands.runAs(ctx, second)(_)(describe))
          out <- answer(in, result)
        yield out

    /** The input becomes the query, which answers; the answer becomes the tool's. */
    def reads[In: McpInput, Q: Tag, Res: Tag, Out: McpOutput, R1, R2](
        query: In => ZIO[R1, String, Q],
        describe: AppError => String = Refusals.describe)(answer: (In, Res) => ZIO[R2, String, Out])
        : McpToolHandlerR[R1 & R2 & Query.Aux[Task, Q, Res]] = tool.handle[
      R1 & R2 & Query.Aux[Task, Q, Res],
      String,
      In,
      Out]: in =>
      for
        asked  <- query(in)
        result <- ZIO.serviceWithZIO[Query.Aux[Task, Q, Res]](Commands.ask(_, asked)(describe))
        out    <- answer(in, result)
      yield out
  end extension
end Adapters
