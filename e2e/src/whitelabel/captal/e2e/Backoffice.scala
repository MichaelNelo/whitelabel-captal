package whitelabel.captal.e2e

import com.jamesward.ziohttp.mcp.client.{McpClient, McpClientConfig, McpClientExtensions}
import com.jamesward.ziohttp.mcp.{McpApps, McpAppsClientSettings}
import whitelabel.captal.mcp.tools.DeployTools
import zio.*
import zio.http.{Client, Header, Headers}
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.codec.JsonCodec

/** The backoffice as an operator's host reaches it: the protocol, through the load balancer, with
  * the admin token. One connection for the whole run.
  */
object Backoffice:
  private lazy val client: McpClient = Run:
    for
      http     <- Client.default.build.provideEnvironment(ZEnvironment(Run.scope))
      declared <- ZIO
        .fromEither(McpClientExtensions(McpApps.clientExtension(McpAppsClientSettings.Html)))
        .mapError(error => IllegalStateException(s"Client extensions: $error"))
      connected <- McpClient
        .connect(
          McpClientConfig(
            serverUrl = Run.setting("E2E_MCP_URL"),
            headers = Headers(Header.Authorization.Bearer(Run.setting("E2E_MCP_TOKEN"))),
            onInputRequest = None),
          declared
        )
        .mapError(error => AssertionError(s"connect: $error"))
        .provideEnvironment(http ++ ZEnvironment(Run.scope))
    yield connected

  /** The arguments as JSON, from the tool's own request type. */
  private def args[A](value: A)(using schema: Schema[A]): Json.Obj = Json
    .decoder
    .decodeJson(JsonCodec.jsonEncoder(schema).encodeJson(value, None))
    .toOption
    .flatMap(_.asObject)
    .getOrElse(Json.Obj())

  /** One call, failing with the tool's own words and its name. */
  def tool[A: Schema, B: Schema](name: String, in: A): B = Run:
    client.callToolAs[B](name, args(in)).mapError(error => AssertionError(s"$name: $error"))

  /** Follows a run through `get_deploy_status` until it is no longer running, as a model would, and
    * fails unless it succeeded.
    */
  def awaitRun(started: DeployTools.RunView): DeployTools.RunView =
    val finished = Run:
      client
        .callToolAs[DeployTools.RunView](
          "get_deploy_status",
          args(DeployTools.StatusRequest(Some(started.runId), None)))
        .mapError(error => AssertionError(s"get_deploy_status: $error"))
        .repeat(Schedule.spaced(3.seconds) *> Schedule.recurWhile(_.status == "running"))
        .timeoutFail(
          AssertionError(s"The ${started.operation} of ${started.slug} ran past 10 minutes"))(
          10.minutes)
    if finished.status != "succeeded" then
      throw AssertionError(
        s"The ${finished.operation} of ${finished.slug} ended ${finished.status}: ${finished
            .steps
            .map(step => s"${step.name}=${step.state}${step.detail.fold("")(d => s" ($d)")}")
            .mkString(", ")}")
    finished
end Backoffice
