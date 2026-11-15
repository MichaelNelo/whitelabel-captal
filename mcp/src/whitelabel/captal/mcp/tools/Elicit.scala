package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import zio.*
import zio.json.ast.Json

/** Questions put to the operator in the middle of a call, through the host. */
object Elicit:
  /** A yes or no to `message`. `None` is no answer: declined, dismissed, or a host that cannot ask
    * — `McpToolContext.elicit` fails for those, which this turns into none rather than an error.
    */
  def yesNo(ctx: McpToolContext, message: String): UIO[Option[Boolean]] = ctx
    .elicit(
      message,
      Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" ->
          Json.Obj(
            "confirm" ->
              Json.Obj(
                "type"        -> Json.Str("boolean"),
                "description" -> Json.Str("True to go ahead."))),
        "required" -> Json.Arr(Json.Str("confirm"))
      )
    )
    .option
    .map(
      _.flatMap(answered =>
        Option.when(answered.action == "accept")(
          answered.content.flatMap(_.get("confirm")).flatMap(_.asBoolean).getOrElse(true))))
end Elicit
