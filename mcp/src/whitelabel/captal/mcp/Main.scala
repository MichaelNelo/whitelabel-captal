package whitelabel.captal.mcp

import io.getquill.jdbczio.Quill
import whitelabel.captal.platform.RqliteDataSource
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.http.Server

/** Entry point for the backoffice MCP server.
  *
  * Stateless routes on purpose: the 2026-07-28 revision makes the core protocol stateless, and a
  * server with no per-connection state is one that can sit behind an ALB with more than one task
  * without sticky sessions.
  *
  * Deliberately does NOT run Flyway. The API owns the schema; a second process migrating the same
  * rqlite cluster on startup is a race with no upside.
  */
object Main extends ZIOAppDefault:
  private val DefaultPort = 8081

  private def port: UIO[Int] = System
    .env("MCP_PORT")
    .orDie
    .map(_.flatMap(_.toIntOption).getOrElse(DefaultPort))

  /** Refuses to start without an admin token rather than generating one.
    *
    * A generated token would have to be shown, and the only channel at startup is the log — which
    * is CloudWatch, the last place a credential holding every scope belongs. Supplied instead by
    * whoever deploys, where secrets are already handled.
    *
    * It is also the recovery path. This database is known to lose data, and a seeded admin means
    * recovery is a restart: the row is written again from the same value and every `.mcp.json`
    * still works. A generated one would lock every operator out on each loss.
    */
  private def adminToken: ZIO[Any, Throwable, String] = System
    .env("MCP_ADMIN_TOKEN")
    .orDie
    .map(_.map(_.trim).filter(_.nonEmpty))
    .someOrFail(
      new IllegalStateException(
        "MCP_ADMIN_TOKEN is not set. It seeds the token every other one is issued from; operators get theirs from create_token, not from the environment."))

  /** Scheme, host and port of the address clients reach this server at.
    *
    * Derived from `MCP_RESOURCE_URI` rather than configured again: that is already declared as the
    * address this server answers to, and two settings for one fact is how they end up disagreeing.
    */
  private def originOf(resourceUri: String): String =
    val parsed = java.net.URI.create(resourceUri)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

  def run: ZIO[Any, Throwable, Unit] =
    for
      p        <- port
      admin    <- adminToken
      resource <- Auth.resourceUri
      // Before the server is assembled: the registry owns the Tasks runtime, and one that fails to
      // validate would otherwise become a server that starts and answers every call synchronously.
      extensions <- CaptalMcp.extensions
      _          <- SkillsExtension.logSkipped
      // Where this server is reached. Its pages post there and declare it in their CSP, so it has
      // to be the address a browser sees rather than the port we bound — behind an ALB those
      // differ, and a guess that is close but wrong fails only when the operator presses Save.
      uiOrigin = originOf(resource.toString)
      // Counted rather than carrying a literal for the video tools, which is what this did — a
      // `+ 3` that stayed 3 while the list grew.
      tools =
        LocationTools.all.size + I18nTools.all.size + AdvertiserTools.all.size +
          SurveyTools.all.size + AssetTools.all.size + UnifiTools.all(uiOrigin).size +
          TokenTools.all.size + SkillTools.all.size + VideoTools.all.size + Readiness.all.size
      // Its own schema, and a check that the API has already laid down the rest. Before anything
      // is served: the first request would otherwise arrive at a tokens table that is not there.
      _ <- McpMigrate.run
      _ <- ZIO
        // `Option[InfraStore]` rather than `InfraStore`: a deployment that only edits content has
        // no cluster configured and no clients to build, and demanding the service here would stop
        // such a server from starting at all. The layer answers `None` and the deploy tools are
        // simply not registered.
        .environment[QuillSqlite & AssetStore & Option[InfraStore]]
        .flatMap: env =>
          val infra = env.get[Option[InfraStore]]
          // Asked rather than assumed, because the answer is otherwise found by an operator opening
          // a page that renders and does nothing. A warning and not a failure: the artifact belongs
          // to `client.publish`, so refusing to start would make this server depend on something it
          // does not own — and a new environment reaches here before that has ever run.
          val checkScript = env
            .get[AssetStore]
            .verifyScriptDelivery
            // Its own client, scoped to the check. Building it can fail, and that failure is the
            // same kind of news as a 404: something to report, never a reason not to serve.
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
            .flatMap: origin =>
              // Read off the same store, so the origin the pages declare and the URL they name
              // cannot disagree — the failure if they did is a page that renders and does nothing.
              val scriptOrigin = env.get[AssetStore].scriptOrigin
              Server.serve(
                UiRoutes.routes ++ OauthMetadata.routes(uiOrigin) ++
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
          // The deploy tools are added here rather than to `tools` above because whether they exist
          // depends on `infra`, which is only known inside this block. Counted for the same reason
          // as the rest: the number has to track the list, and this one varies per deployment.
          val registered = tools + DeployTools.all(infra).size
          seeded *> checkScript *>
            ZIO.logInfo(
              s"MCP backoffice on :$p as $resource — ${Skills.all.size} skills, ${CaptalMcp
                  .resources
                  .size} resources, $registered tools${
                  if infra.isDefined then
                    ""
                  else
                    " (no ECS_CLUSTER, so the deploy, pause and destroy tools are not registered)"
                }") *> serve
        .provide(
          Server.defaultWithPort(p),
          RqliteDataSource.layer,
          AssetStore.layer,
          // Read at startup like the bucket and the distribution, and for the same reason: a
          // cluster name that turns out to be wrong should stop the server rather than surface on
          // the first deploy, which is the one call where a mistake creates something.
          DeployTargets.layer,
          InfraStore.layer,
          Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)
        )
    yield ()
end Main
