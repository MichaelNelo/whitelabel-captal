package whitelabel.captal.mcp

import io.getquill.jdbczio.Quill
import whitelabel.captal.infra.RqliteDataSource
import whitelabel.captal.infra.schema.QuillSqlite
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
        .environment[QuillSqlite & AssetStore]
        .flatMap: env =>
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
                    .server(Auth.mcpAuth(resource), env, origin, scriptOrigin, uiOrigin)
                    .statelessRoutes)
          seeded *>
            ZIO.logInfo(
              s"MCP backoffice on :$p as $resource — ${Skills.all.size} skills, ${CaptalMcp
                  .resources
                  .size} resources, $tools tools") *> serve
        .provide(
          Server.defaultWithPort(p),
          RqliteDataSource.layer,
          AssetStore.layer,
          Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase))
    yield ()
end Main
