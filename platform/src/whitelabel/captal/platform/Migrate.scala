package whitelabel.captal.platform

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
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
  def migrateTo(url: String, location: String, historyTable: String): Task[Int] =
    Lock(url):
      Fly4s
        .makeFor[Task](
          ZIO.attempt(RqliteDataSource.create(url)),
          config = fly4sConfig.copy(locations = List(Location(location)), table = historyTable))
        .use(_.migrate.map(_.migrationsExecuted))

  /** A mutex across every process that might migrate at the same time: a single statement goes
    * through Raft, so of N concurrent `INSERT`s of one primary key exactly one takes effect and
    * every node agrees which. Must stay reachable only through [[migrateTo]] — holding it around
    * anything but a migration blocks migrations.
    */
  private object Lock:

    private val Table = "migration_lock"

    /** Always 1. The table holds at most one row and the primary key on it is the mutex. */
    private val Row = 1

    private val PollInterval = 2.seconds

    private val Timeout = 10.minutes

    private def withConnection[A](url: String)(use: java.sql.Connection => A): Task[A] = ZIO
      .attemptBlocking:
        val connection = RqliteDataSource.create(url).getConnection()
        try use(connection)
        finally connection.close()

    private def update(url: String, sql: String): Task[Int] =
      withConnection(url): connection =>
        val statement = connection.createStatement()
        try statement.executeUpdate(sql)
        finally statement.close()

    private def ensureTable(url: String): Task[Unit] =
      update(
        url,
        s"CREATE TABLE IF NOT EXISTS $Table (id INTEGER PRIMARY KEY, holder TEXT NOT NULL)").unit

    /** Who holds the lock, or None when it is free. */
    private def holderOf(url: String): Task[Option[String]] =
      withConnection(url): connection =>
        val statement = connection.createStatement()
        try
          val results = statement.executeQuery(s"SELECT holder FROM $Table WHERE id = $Row")
          if results.next() then
            Some(results.getString("holder"))
          else
            None
        finally
          statement.close()

    /** Insert, then read back who actually holds it: the read-back is the answer and the insert
      * only a bid. rqlite can report a statement it refused as zero affected rows inside an HTTP
      * 200, so the insert's own result decides nothing.
      */
    private def tryAcquire(url: String, holder: String): Task[Either[String, Boolean]] =
      for
        bid  <- update(url, s"INSERT INTO $Table (id, holder) VALUES ($Row, '$holder')").either
        held <- holderOf(url)
      yield
        if held.contains(holder) then
          Right(true)
        else if held.nonEmpty then
          // Someone else got there first.
          Right(false)
        else
          // Nothing holds it and the bid did not land, so waiting would never end.
          Left(
            bid match
              case Left(e) =>
                Option(e.getMessage).getOrElse(e.toString)
              case Right(rows) =>
                s"the insert affected $rows rows and raised nothing, which is how this driver reports a statement the database refused")

    private def release(url: String, holder: String): Task[Unit] =
      update(url, s"DELETE FROM $Table WHERE id = $Row AND holder = '$holder'").unit

    /** Runs `effect` with nothing else migrating, or fails. No heartbeat and no takeover: a holder
      * that dies without releasing blocks every other migrator until its row in `migration_lock` is
      * deleted by hand.
      */
    def apply[A](url: String)(effect: Task[A]): Task[A] =
      val holder = holderId()

      def attempt: Task[A] = tryAcquire(url, holder).flatMap:
        case Right(true) =>
          ZIO.logInfo(s"Migration lock taken by $holder") *>
            effect.ensuring(release(url, holder).orDie)
        case Right(false) =>
          holderOf(url).flatMap: other =>
            ZIO.logInfo(s"Waiting for the migration lock, held by ${other.getOrElse("nobody")}") *>
              attempt.delay(PollInterval)
        case Left(error) =>
          ZIO.fail(
            new IllegalStateException(
              s"Could not take the migration lock and nothing holds it, so waiting would never end. The bid failed with: $error. Check the shape of '$Table' — it is created here with (id, holder) and an older column still present would reject every insert."))

      ensureTable(url) *>
        attempt.timeoutFail(
          new IllegalStateException(
            s"Could not take the migration lock within ${Timeout
                .toMinutes} minutes. Another migrator is holding it, so nothing has been changed here. If it died mid-migration its schema is half applied and Flyway has a failed row for it: repair that, then delete the row in '$Table'."))(
          Timeout)
    end apply

    /** Lock holder id: hostname plus a random UUID. Every statement here is built by interpolation,
      * so the hostname is narrowed to letters, digits, `-` and `.`.
      */
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

  /** The highest successfully applied version in a history table, or None when the table is not
    * there yet. Plain JDBC: building a Flyway instance can create or baseline the history it is
    * asked about.
    */
  def highestApplied(url: String, historyTable: String): Task[Option[Int]] = ZIO
    .attemptBlocking:
      val connection = RqliteDataSource.create(url).getConnection()
      try
        val statement = connection.createStatement()
        try
          val results = statement.executeQuery(
            s"SELECT version FROM $historyTable WHERE success = 1")
          var highest = Option.empty[Int]
          while results.next() do
            val version = Option(results.getString("version")).flatMap(_.toIntOption)
            highest =
              (highest, version) match
                case (Some(a), Some(b)) =>
                  Some(a.max(b))
                case (a, b) =>
                  a.orElse(b)
          highest
        finally
          statement.close()
      finally
        connection.close()
    .catchSome:
      // No history table means nothing has migrated yet.
      case _: java.sql.SQLException =>
        ZIO.none

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
