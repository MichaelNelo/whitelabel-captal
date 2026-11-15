package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.auth.Principal
import zio.*
import zio.json.ast.Json

/** A tool call as the authenticated endpoint makes it for the operator token `subject`: what the
  * tools that record who changed something read. It asks nothing of the client, which a test has
  * none of to ask.
  */
final class OperatorContext(subject: String) extends McpToolContext:
  override val principal: Option[Principal] = Some(
    Principal(
      subject = Some(subject),
      clientId = None,
      scopes = Set.empty,
      audience = Set.empty,
      issuer = None,
      expiresAt = None,
      raw = "",
      claims = Json.Obj()))

  private def none = ZIO.dieMessage("A test's tool call has no client to ask")

  def log(level: com.jamesward.ziohttp.mcp.LogLevel, message: String): UIO[Unit] = ZIO.unit
  def progress(current: Double, total: Double, message: Option[String] = None): UIO[Unit] = ZIO.unit
  def sample(prompt: String, maxTokens: Int = 100): ZIO[Any, ToolError, SamplingResult] = none
  def elicit(message: String, schema: Json.Obj): ZIO[Any, ToolError, ElicitationResult] = none
  def listRoots: ZIO[Any, ToolError, Chunk[Root]] = none
  def sample(id: String, prompt: String, maxTokens: Int): ZIO[Any, ToolError, SamplingResult] = none

  def elicit(
      id: String,
      message: String,
      schema: Json.Obj): ZIO[Any, ToolError, ElicitationResult] = none

  def listRoots(id: String): ZIO[Any, ToolError, Chunk[Root]] = none
  def inputs(specs: InputSpec*): ZIO[Any, ToolError, InputResults] = none
end OperatorContext

object OperatorContext:
  /** The operator the specs make their changes as. */
  val Default: OperatorContext = OperatorContext("spec-operator")
