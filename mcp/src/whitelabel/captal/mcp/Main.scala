package whitelabel.captal.mcp

import io.getquill.jdbczio.Quill
import whitelabel.captal.cdc.protocol.ChangeFeedFollower
import whitelabel.captal.mcp.access.{Auth, OauthMetadata, Tokens}
import whitelabel.captal.mcp.apps.{RunRoutes, UiRoutes}
import whitelabel.captal.mcp.aws.{AssetStore, DeployTargets, InfraStore}
import whitelabel.captal.mcp.protocol.{Skills, SkillsExtension}
import whitelabel.captal.mcp.resources.Readiness
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AssetTools,
  DeployTools,
  I18nTools,
  LocationTools,
  PreviewTools,
  RecoveryTools,
  SkillTools,
  SurveyTools,
  TermsTools,
  TokenTools,
  UnifiTools,
  VideoFileCollector,
  VideoTools
}
import whitelabel.captal.platform.RqliteDataSource
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.http.{Method, Response, Routes, Server, handler}

/** Entry point for the backoffice MCP server.
  *
  * Stateless routes, so it can sit behind an ALB with more than one task and no sticky sessions.
  * [[McpMigrate]] lays down every schema it needs, the API's included, before it serves.
  */
object Main extends ZIOAppDefault:
  private val DefaultPort = 8081

  private def port: UIO[Int] = System
    .env("MCP_PORT")
    .orDie
    .map(_.flatMap(_.toIntOption).getOrElse(DefaultPort))

  /** `MCP_ADMIN_TOKEN`, trimmed and non-empty. Refuses to start without it rather than generating
    * one: it is also the recovery path after this database loses data.
    */
  private def adminToken: ZIO[Any, Throwable, String] = System
    .env("MCP_ADMIN_TOKEN")
    .orDie
    .map(_.map(_.trim).filter(_.nonEmpty))
    .someOrFail(
      new IllegalStateException(
        "MCP_ADMIN_TOKEN is not set. It seeds the token every other one is issued from; operators get theirs from create_token, not from the environment."))

  /** Scheme, host and port of the address clients reach this server at, derived from
    * `MCP_RESOURCE_URI` rather than configured again.
    */
  private def originOf(resourceUri: String): String =
    val parsed = java.net.URI.create(resourceUri)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

  /** Follows the change feed at `CDC_FEED_ADDRESS` (`host:port`) for the collector, for as long as
    * the server runs. A warning and not a failure without it: the collector still runs at startup
    * and on every replacement, and playbacks open at startup still expire; only playbacks that
    * start or end afterwards go unheard.
    */
  private def followChanges(collector: VideoFileCollector.Running): URIO[Scope, Unit] = System
    .env("CDC_FEED_ADDRESS")
    .orDie
    .map(_.map(_.trim).filter(_.nonEmpty))
    .flatMap:
      case Some(address) =>
        ZIO.logInfo(s"Following the change feed at $address") *>
          VideoFileCollector.follow(collector, ChangeFeedFollower.follow(address)).forkScoped.unit
      case None =>
        ZIO.logWarning(
          "No CDC_FEED_ADDRESS: playbacks that start or end from now on are not heard of until the next restart")

  /** The load balancer's health check, `GET /health` answering `ok`: served once the server is, so
    * a task still migrating or one that failed to start is not sent requests.
    */
  private val health = Routes(Method.GET / "health" -> handler(Response.text("ok")))

  def run: ZIO[Any, Throwable, Unit] =
    for
      p        <- port
      admin    <- adminToken
      resource <- Auth.resourceUri
      // Validated before the server is assembled: a registry that fails to build would otherwise
      // become a server that starts and answers every call synchronously.
      extensions <- CaptalMcp.extensions
      _          <- SkillsExtension.logSkipped
      // Where this server is reached. Its pages post there and declare it in their CSP, so it has
      // to be the address a browser sees rather than the port we bound — behind an ALB those
      // differ, and a guess that is close but wrong fails only when the operator presses Save.
      uiOrigin = originOf(resource.toString)
      // Counted rather than carrying a literal, so the number tracks the lists.
      tools =
        LocationTools.all.size + I18nTools.all.size + AdvertiserTools.all.size +
          SurveyTools.all.size + AssetTools.all.size + UnifiTools.all(uiOrigin).size +
          RecoveryTools.all.size + TermsTools.all.size + TokenTools.all.size + SkillTools.all.size +
          VideoTools.all.size + PreviewTools.all.size + Readiness.all.size
      // The API's schema and its own. Before anything is served: the first request would otherwise
      // arrive at a tokens table that is not there.
      _ <- McpMigrate.run
      _ <- ZIO
        // `Option[InfraStore]`: a deployment that only edits content has no cluster configured and
        // no clients to build, so the layer answers `None` and the deploy tools are not registered.
        .environment[QuillSqlite & AssetStore & Option[InfraStore]]
        .flatMap: env =>
          val infra = env.get[Option[InfraStore]]
          // A warning and not a failure: the published script belongs to `client.publish`, and a
          // new environment reaches here before that has ever run.
          val checkScript = env
            .get[AssetStore]
            .verifyScriptDelivery
            // Its own client, scoped to the check; a failure to build one is news to report rather
            // than a reason not to serve.
            .provide(zio.http.Client.default)
            .catchAll(e => ZIO.succeed(Some(s"could not be checked: ${e.getMessage}")))
            .flatMap:
              case Some(problem) =>
                ZIO.logWarning(s"The MCP App pages' script $problem")
              case None =>
                ZIO.logInfo("MCP App script reachable with CORS")
          val seeded = Tokens
            .seedAdmin(admin, "admin")
            .mapError(new IllegalStateException(_))
            .flatMap: rotated =>
              ZIO.logInfo(
                if rotated then
                  "Admin token seeded from MCP_ADMIN_TOKEN"
                else
                  "Admin token already current")
          val serve = ZIO
            .serviceWithZIO[AssetStore](_.uploadOrigin)
            // Startup, not a request: a server that cannot name the origin its own upload page
            // must reach would serve a page whose PUT the browser blocks, and nothing would say so.
            .mapError(new IllegalStateException(_))
            .zipLeft(VideoFileCollector.runner.flatMap(followChanges))
            .flatMap: origin =>
              // Read off the same store, so the origin the pages declare and the URL they name
              // cannot disagree.
              val scriptOrigin = env.get[AssetStore].scriptOrigin
              Server.serve(
                health ++ UiRoutes.routes ++ RunRoutes.routes ++ OauthMetadata.routes(uiOrigin) ++
                  CaptalMcp
                    .server(
                      Auth.mcpAuth(resource),
                      env,
                      origin,
                      scriptOrigin,
                      uiOrigin,
                      infra,
                      extensions)
                    .statelessRoutes)
          // Added here rather than to `tools` above because whether the deploy tools exist depends
          // on `infra`, which is only known inside this block.
          val registered = tools + DeployTools.all(infra, uiOrigin).size
          seeded *> checkScript *>
            ZIO.logInfo(
              s"MCP backoffice on :$p as $resource — ${Skills.all.size} skills, ${CaptalMcp
                  .resources
                  .size} resources, $registered tools${
                  if infra.isDefined then
                    ""
                  else
                    " (no ECS_CLUSTER, so the deploy, pause and destroy tools are not registered)"
                }") *> ZIO.scoped(serve)
        .provide(
          Server.defaultWithPort(p),
          RqliteDataSource.layer,
          AssetStore.layer,
          // Read at startup like the bucket and the distribution: a cluster name that turns out to
          // be wrong should stop the server rather than surface on the first deploy.
          DeployTargets.layer,
          InfraStore.layer,
          Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)
        )
    yield ()
end Main
