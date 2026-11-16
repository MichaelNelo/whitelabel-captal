package whitelabel.captal.platform

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import whitelabel.captal.platform.locks.{Acquired, DistributedLock, Redlock}
import zio.*
import zio.interop.catz.*

object Migrate extends ZIOAppDefault:

  private val fly4sConfig = Fly4sConfig
    .default
    .copy(
      locations = List(Location("db/migration")),
      group = false,
      mixed = true,
      baselineOnMigrate = true,
      baselineVersion = MigrationVersion("0"))

  /** The API's own history table, and Flyway's default. */
  val ApiHistoryTable: String = fly4sConfig.table

  /** Runs one set of migrations against one history table. Takes [[Lock]] itself, so callers must
    * not.
    */
  def migrateTo(url: String, location: String, historyTable: String): Task[Int] = ZIO.scoped:
    for
      locks    <- Redlock.layer.build.map(_.get)
      _        <- Lock.hold(locks)
      executed <- Fly4s
        .makeFor[Task](
          ZIO.attempt(RqliteDataSource.create(url)),
          config = fly4sConfig.copy(locations = List(Location(location)), table = historyTable))
        .use(_.migrate.map(_.migrationsExecuted))
    yield executed

  /** A mutex across every process that might migrate at the same time — the backoffice and every
    * location's API — in Redis, at `REDIS_LOCK_ADDRESSES`. Must stay reachable only through
    * [[migrateTo]]: holding it around anything but a migration blocks migrations.
    *
    * It lasts as long as its holder keeps extending it, so a migrator that dies frees it within
    * [[Ttl]]. One that died mid-migration leaves its schema half applied, which Flyway refuses to
    * migrate past until it is repaired: the next migrator fails on that, rather than on a lock
    * nobody would ever let go.
    */
  private object Lock:

    private val Resource = "captal:migrations"

    private val Ttl = 30.seconds

    private val PollInterval = 2.seconds

    private val Timeout = 10.minutes

    /** Takes the lock for this process, waiting while another migrator holds it, and keeps it for
      * as long as the scope is open. A lock that cannot be extended part way through is logged and
      * the migration goes on: Flyway is not stopped part way.
      */
    def hold(locks: DistributedLock): ZIO[Scope, Throwable, Unit] =
      val holder = holderId()

      def attempt: Task[Unit] = locks
        .acquire(Resource, holder, Ttl)
        .flatMap:
          case Acquired.Held(_) =>
            ZIO.logInfo(s"Migration lock taken by $holder")
          case Acquired.HeldBy(other) =>
            ZIO.logInfo(s"Waiting for the migration lock, held by $other") *>
              attempt.delay(PollInterval)
          case Acquired.Unanswered =>
            ZIO.logWarning("The migration lock's Redis instances did not answer; trying again") *>
              attempt.delay(PollInterval)

      for
        _ <-
          attempt.timeoutFail(
            new IllegalStateException(
              s"Could not take the migration lock within ${Timeout
                  .toMinutes} minutes: another migrator held it, or its Redis instances did not answer. Nothing has been changed here."))(
            Timeout)
        lease <- locks.keep(Resource, holder, Ttl)
        _     <- (
          lease.lost *>
            ZIO.logWarning(
              "The migration lock could not be extended while migrating: another migrator may start alongside this one")
        ).forkScoped
      yield ()
      end for
    end hold

    /** Lock holder id: hostname plus a random UUID, which the waiting migrators log. */
    private def holderId(): String =
      val host =
        try
          java.net.InetAddress.getLocalHost.getHostName
        catch
          case _: Throwable =>
            "unknown"
      val safe = host.filter(c => c.isLetterOrDigit || c == '-' || c == '.')
      s"${
          if safe.isEmpty then
            "unknown"
          else
            safe
        }-${java.util.UUID.randomUUID()}"
  end Lock

  override val run: ZIO[Any, Throwable, Unit] = ZIO
    .attempt:
      val config = ConfigFactory.load()
      config.getString("database.jdbcUrl")
    .flatMap: url =>
      for
        _        <- ZIO.logInfo("Running database migrations...")
        executed <- migrateTo(url, "db/migration", ApiHistoryTable)
        _        <- ZIO.logInfo(s"Migrations complete: $executed executed")
      yield ()
end Migrate
