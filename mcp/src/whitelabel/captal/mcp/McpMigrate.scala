package whitelabel.captal.mcp

import com.typesafe.config.ConfigFactory
import whitelabel.captal.platform.Migrate
import zio.*

/** The MCP server's own schema, and a check that the API has already laid down the rest.
  *
  * Operator tokens are this server's alone, so they get their own Flyway location and history
  * table; the API owns every other table and must have migrated first, which is why this checks
  * rather than migrates them.
  *
  * A `ZIOAppDefault` as well as something [[Main]] calls, so the schema can be laid down without
  * starting a server. Flyway cannot migrate concurrently on SQLite, so one process must finish
  * first.
  */
object McpMigrate extends ZIOAppDefault:

  private val Location = "db/migration-mcp"
  private val HistoryTable = "flyway_schema_history_mcp"

  /** The API schema version this server's tools are written against. */
  private val RequiredApiVersion = 21

  private def jdbcUrl: Task[String] = ZIO.attempt(
    ConfigFactory.load().getString("database.jdbcUrl"))

  /** Fails startup when the API schema is behind [[RequiredApiVersion]], or when no API migration
    * has run at all. Deployment order between the two services cannot be enforced here.
    */
  private def requireApiSchema(url: String): ZIO[Any, Throwable, Unit] = Migrate
    .highestApplied(url, Migrate.ApiHistoryTable)
    .flatMap:
      case Some(version) if version >= RequiredApiVersion =>
        ZIO.logInfo(s"API schema at V$version, at or past the required V$RequiredApiVersion")
      case Some(version) =>
        ZIO.fail(
          new IllegalStateException(
            s"The API schema is at V$version but this server needs V$RequiredApiVersion or later. Deploy the API first — it owns those migrations and runs them on startup."))
      case None =>
        ZIO.fail(
          new IllegalStateException(
            s"No API migrations have run against this database. Start the API first: it creates every table the backoffice reads, and this server deliberately does not."))

  /** Own schema only, after the check. Also this app's entry point. */
  override def run: ZIO[Any, Throwable, Unit] =
    for
      url      <- jdbcUrl
      _        <- requireApiSchema(url)
      executed <- Migrate.migrateTo(url, Location, HistoryTable)
      _        <- ZIO.logInfo(s"Backoffice migrations complete: $executed executed")
    yield ()
end McpMigrate
