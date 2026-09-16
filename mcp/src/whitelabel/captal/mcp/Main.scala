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

  def run: ZIO[Any, Throwable, Unit] =
    for
      p <- port
      _ <- ZIO.logInfo(
        s"MCP backoffice on :$p — ${Skills.all.size} skills, ${CaptalMcp
            .resources
            .size} resources, ${I18nTools.all.size} tools")
      _ <- Server
        .serve(CaptalMcp.server().statelessRoutes)
        .provide(
          Server.defaultWithPort(p),
          RqliteDataSource.layer,
          Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase))
    yield ()
end Main
