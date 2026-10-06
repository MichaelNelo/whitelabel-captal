package whitelabel.captal.mcp

import com.typesafe.config.ConfigFactory
import whitelabel.captal.infra.Migrate
import zio.*

/** The MCP server's own schema, and a check that the API has already laid down the rest.
  *
  * This server used to migrate nothing on purpose: the API owns every table the portal reads, and
  * two processes migrating one history table on startup is a race with no upside. That still holds.
  * What changed is that operator tokens moved into the database, and those are this server's alone
  * — nothing in the portal reads them — so they get a schema and a history table of their own.
  *
  * The two histories are separate and the dependency between them is one-way: this server reads
  * tables the API created and migrates only its own. Hence the check rather than a migration — it
  * needs the API to have run, and it must not be the one to run it.
  */
/** A `ZIOAppDefault` as well as something [[Main]] calls, so the schema can be laid down without
  * starting a server. `mcp.test` runs it that way before forking: Flyway cannot migrate
  * concurrently on SQLite — it says so in the log — and the only reliable answer is for a single
  * process to finish first.
  */
object McpMigrate extends ZIOAppDefault:

  private val Location = "db/migration-mcp"
  private val HistoryTable = "flyway_schema_history_mcp"

  /** The API schema version this server's tools are written against.
    *
    * V20 added `is_active` to questions, options, rules and translations — which
    * `set_question_active` and `remove_translation` write — and V21 replaced the videos'
    * `is_active` with the three-state `status` that `create_advertiser_video`,
    * `resume_video_upload` and `confirm_video_upload` all branch on. Against V19 those tools
    * compile and then fail at runtime on a column that is not there, one operator at a time.
    */
  private val RequiredApiVersion = 21

  private def jdbcUrl: Task[String] = ZIO.attempt(
    ConfigFactory.load().getString("database.jdbcUrl"))

  /** Refuses to start rather than starting against a schema that is behind.
    *
    * The failure this prevents is the quiet one: every read-only tool would work, the server would
    * look healthy, and the first write to a column V20 added would fail in front of an operator
    * with a message about SQL. Deployment order between two services is not something this can
    * enforce, so it says so at startup instead of discovering it later.
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
