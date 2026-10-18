package whitelabel.captal.api.suites

import com.typesafe.config.ConfigFactory
import java.sql.Connection
import whitelabel.captal.infra.{Migrate, RqliteDataSource}
// Selective, because `zio.*` and `zio.test.*` both export `test` and neither wins by being second.
// The other suites get away with the wildcard by extending `ZIOSpecDefault`, where `test` arrives
// as an inherited member and shadows both imports; a plain object has no such member.
import zio.{Task, ZIO}
import zio.test.*

/** That `Migrate.Lock` is load-bearing, and not the thing it is easy to mistake it for.
  *
  * This exists because the question came up as "Flyway wraps each migration in a transaction, so
  * does the lock do anything?" — and answering it took racing processes by hand twice, because
  * nothing in the codebase pinned the answer. The transaction is real and it does work; it is just
  * not exclusion. Without this suite the lock reads like belt-and-braces and the next person
  * deletes it.
  *
  * A suite under [[whitelabel.captal.api.E2ETests]] rather than its own `ZIOSpecDefault`, and that
  * is not cosmetic. A second spec class in this module makes Mill hand the two classes to a claim
  * queue and fork *two* JVMs against one rqlite, so this ran concurrently with the rest of the
  * suite — which is how the first version of it wedged `mill __.test` for over an hour. Here it
  * runs in the one JVM, under that spec's `TestAspect.sequential`.
  *
  * Its own location (`db/race`) and its own history table, so it shares nothing with
  * `db/migration`.
  */
object MigrationLockSuite:

  private val url = ConfigFactory.load("test.conf").getString("database.jdbcUrl")

  private val HistoryTable = "flyway_race_history"
  private val MarkerTable = "migration_race_marker"

  /** Six, because that is where racing stopped being probabilistic by hand. Two would pass with the
    * lock removed often enough to be useless as a regression test.
    */
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
    * `migration_lock` is deliberately not touched. The first version dropped it to guarantee a
    * clean start, which meant tearing the mutex table out from under anything else holding it. If a
    * previous run died between acquisition and release the row is still there and these tests fail
    * on the lock's own timeout message, which names the table and what to do about it — the right
    * outcome, because a lock that is genuinely stuck should be visible rather than cleared by the
    * next test that happens to run.
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
  // hangs rather than failing. Measured at nine minutes and still going.
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
            // One migrator reports 1 and the rest report 0. Whoever wins is not interesting; that
            // exactly one does is.
            assertTrue(executed.sum == 1) &&
              // The assertion that fails when the lock goes. Measured with it bypassed: six
              // migrators against one pending `DROP TABLE IF EXISTS` wrote three history rows for
              // the same version at ranks 23, 24 and 25 within 53ms, ten trials out of ten.
              // `installed_rank` is the primary key and `version` is not unique, so migrators that
              // read the same max and increment it land on distinct ranks and never collide.
              assertTrue(marks == 1) && assertTrue(rows == 1)
        ,
        test("a second run over an applied migration is a no-op rather than a second application"):
          // The redeploy that changes no migration, which is most of them. Flyway is what makes this
          // true — it applies only what is pending — and the lock is what stops six of these from
          // deciding "pending" at the same moment.
          for
            executed <- race
            marks    <- count(s"SELECT COUNT(*) FROM $MarkerTable")
          yield assertTrue(executed.sum == 0) && assertTrue(marks == 1)
      ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end MigrationLockSuite
