package whitelabel.captal.api.suites

import java.sql.Connection

import com.typesafe.config.ConfigFactory
import whitelabel.captal.platform.{Migrate, RqliteDataSource}
import zio.test.*
import zio.{Task, ZIO}

/** That `Migrate.Lock` is load-bearing: Flyway wraps each migration in a transaction, and that
  * transaction is real, but it is not exclusion.
  *
  * A suite under [[whitelabel.captal.api.E2ETests]] rather than its own `ZIOSpecDefault`, and that
  * is not cosmetic: a second spec class in this module makes Mill hand the two classes to a claim
  * queue and fork *two* JVMs against one rqlite, running this concurrently with the rest of the
  * suite. Here it runs in the one JVM, under that spec's `TestAspect.sequential`.
  *
  * Its own location (`db/race`) and its own history table, so it shares nothing with
  * `db/migration`.
  */
object MigrationLockSuite:

  private val url = ConfigFactory.load("test.conf").getString("database.jdbcUrl")

  private val HistoryTable = "flyway_race_history"
  private val MarkerTable = "migration_race_marker"

  /** Six concurrent migrators; fewer pass often enough with the lock removed to regress nothing. */
  private val Migrators = 6

  private def withConnection[A](use: Connection => A): Task[A] = ZIO.attemptBlocking:
    val connection = RqliteDataSource.create(url).getConnection()
    try use(connection)
    finally connection.close()

  private def execute(sql: String): Task[Unit] = withConnection: connection =>
    val statement = connection.createStatement()
    try statement.executeUpdate(sql)
    finally statement.close()
    ()

  private def count(sql: String): Task[Int] = withConnection: connection =>
    val statement = connection.createStatement()
    try
      val results = statement.executeQuery(sql)
      if results.next() then
        results.getInt(1)
      else
        0
    finally
      statement.close()

  /** Back to never-migrated — this suite's two tables and nothing else.
    *
    * `migration_lock` is deliberately not touched: if a previous run died between acquisition and
    * release the row is still there and these tests fail on the lock's own timeout message, which
    * names the table and what to do about it.
    */
  private val reset =
    execute(s"DROP TABLE IF EXISTS $MarkerTable") *> execute(s"DROP TABLE IF EXISTS $HistoryTable")

  private val race =
    ZIO.foreachPar(1 to Migrators): _ =>
      Migrate.migrateTo(url, "db/race", HistoryTable)

  // `withLiveClock` is load-bearing, not hygiene. `Lock` waits with `delay`, which reads the Clock
  // from ZIO's default services — and ZIO Test replaces that with `TestClock`, which only advances
  // when a test advances it. Without this the losing migrators sleep on a clock nobody moves, and
  // `Lock`'s own ten-minute timeout cannot fire either because it reads the same clock: the suite
  // hangs rather than failing.
  //
  // `zio.test.suite` qualified, like every other suite here: the `val suite` being defined is
  // itself in scope and shadows the imported one.
  val suite: Spec[Any, Throwable] =
    zio
      .test
      .suite("Migration lock")(
        test(s"$Migrators migrators at once apply one pending migration exactly once"):
          for
            _        <- reset
            executed <- race
            marks    <- count(s"SELECT COUNT(*) FROM $MarkerTable")
            rows     <- count(s"SELECT COUNT(*) FROM $HistoryTable WHERE version = '1'")
          yield
            // One migrator reports 1 and the rest report 0; which one wins is not interesting.
            assertTrue(executed.sum == 1) &&
              // The assertions that fail when the lock goes: `installed_rank` is the primary key
              // and `version` is not unique, so migrators that read the same max and increment it
              // land on distinct ranks and never collide.
              assertTrue(marks == 1) && assertTrue(rows == 1)
        ,
        test("a second run over an applied migration is a no-op rather than a second application"):
          // The redeploy that changes no migration, which is most of them: Flyway applies only what
          // is pending, and the lock stops six of these deciding "pending" at the same moment.
          for
            executed <- race
            marks    <- count(s"SELECT COUNT(*) FROM $MarkerTable")
          yield assertTrue(executed.sum == 0) && assertTrue(marks == 1)
      ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end MigrationLockSuite
