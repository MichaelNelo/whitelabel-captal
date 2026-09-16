package whitelabel.captal.mcp

import io.getquill.jdbczio.Quill
import whitelabel.captal.infra.RqliteDataSource
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

  /** Refuses to start without tokens rather than falling back to an open server.
    *
    * This server mutates production data. A missing environment variable is a deployment mistake,
    * and the failure mode of guessing "open" would be silent and total.
    */
  private def operators: ZIO[Any, Throwable, List[(String, Auth.Operator)]] = System
    .env("MCP_TOKENS")
    .orDie
    .someOrFail(
      new IllegalStateException(
        "MCP_TOKENS is not set. Expected secret|name|scope,scope entries separated by ';'. Scopes: i18n:write, content:write."))
    .flatMap: spec =>
      ZIO.fromEither(Auth.parse(spec)).mapError(new IllegalStateException(_))

  def run: ZIO[Any, Throwable, Unit] =
    for
      p        <- port
      ops      <- operators
      resource <- Auth.resourceUri
      tools =
        I18nTools.all.size + AdvertiserTools.all.size + SurveyTools.all.size + AssetTools.all.size
      _ <- ZIO.logInfo(
        s"MCP backoffice on :$p as $resource — ${Skills.all.size} skills, ${CaptalMcp
            .resources
            .size} resources, $tools tools, ${ops.size} operator(s)")
      _ <- Server
        .serve(CaptalMcp.server(Auth.mcpAuth(ops, resource)).statelessRoutes)
        .provide(
          Server.defaultWithPort(p),
          RqliteDataSource.layer,
          AssetStore.layer,
          Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase))
    yield ()
end Main
