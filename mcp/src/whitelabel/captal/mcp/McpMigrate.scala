package whitelabel.captal.mcp

import com.typesafe.config.ConfigFactory
import whitelabel.captal.platform.Migrate
import zio.*

/** Every migration this server needs: the API's, which create the tables its tools write, then its
  * own. So it starts on an empty database as on one a location's API has already migrated, and a
  * new environment's first location can be created at all, since the backoffice is what creates it.
  *
  * Operator tokens are this server's alone, so they get their own Flyway location and history
  * table. The API's are run from the scripts this server was built with, the same `platform` the
  * API's image carries in a release; whichever of the two migrates first leaves the other nothing
  * to do. Each set takes `Migrate`'s lock, so an API starting beside this does not race it.
  *
  * A `ZIOAppDefault` as well as something [[Main]] calls, so the schema can be laid down without
  * starting a server.
  */
object McpMigrate extends ZIOAppDefault:

  private val Location = "db/migration-mcp"
  private val HistoryTable = "flyway_schema_history_mcp"

  private def jdbcUrl: Task[String] = ZIO.attempt(
    ConfigFactory.load().getString("database.jdbcUrl"))

  /** How long a start waits for a database that does not answer: rqlite's nodes are replaced
    * whenever its task definition changes, and a release can start this server meanwhile.
    */
  private val Patience = 5.minutes

  /** Whether `error` is the database not answering, rather than a migration that failed. */
  private def unreachable(error: Throwable): Boolean = Iterator
    .iterate(error)(_.getCause)
    .takeWhile(_ != null)
    .exists(cause =>
      (cause +: cause.getSuppressed.toSeq).exists(_.isInstanceOf[java.net.ConnectException]))

  /** One set, waiting out a database that does not answer yet; any other failure fails at once. */
  private def migrate(url: String, location: String, historyTable: String): Task[Int] = Migrate
    .migrateTo(url, location, historyTable)
    .tapError(error =>
      ZIO.when(unreachable(error))(
        ZIO.logWarning("The database does not answer yet; trying again in 5 seconds")))
    .retry(
      (Schedule.spaced(5.seconds) && Schedule.recurWhile[Throwable](unreachable)).upTo(Patience))

  /** The API's schema, then this server's own. Also this app's entry point. */
  override def run: ZIO[Any, Throwable, Unit] =
    for
      url <- jdbcUrl
      api <- migrate(url, "db/migration", Migrate.ApiHistoryTable)
      _   <- ZIO.logInfo(s"API migrations complete: $api executed")
      own <- migrate(url, Location, HistoryTable)
      _   <- ZIO.logInfo(s"Backoffice migrations complete: $own executed")
    yield ()
end McpMigrate
