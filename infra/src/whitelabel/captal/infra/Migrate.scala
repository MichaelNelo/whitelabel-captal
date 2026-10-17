package whitelabel.captal.infra

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import zio.*
import zio.interop.catz.*

object Migrate extends ZIOAppDefault:

  private val writePrefix = Set(
    "INSERT",
    "UPDATE",
    "DELETE",
    "CREATE",
    "ALTER",
    "DROP",
    "REPLACE",
    "PRAGMA")

  /** Every leading comment, not just the first.
    *
    * This used to skip one and then decide, which meant a migration opening with more than one `--`
    * line was still looking at a comment when it checked for a write keyword — so it was classified
    * as a read, sent to `/db/query`, and silently did nothing while Flyway recorded it as applied.
    * That is the worst shape this failure can take: the history says the schema changed and the
    * database disagrees.
    */
  @annotation.tailrec
  private def skipComments(sql: String): String =
    val trimmed = sql.stripLeading()
    if trimmed.startsWith("--") then
      skipComments(trimmed.dropWhile(_ != '\n'))
    else if trimmed.startsWith("/*") then
      val end = trimmed.indexOf("*/")
      if end >= 0 then
        skipComments(trimmed.drop(end + 2))
      else
        trimmed
    else
      trimmed

  /** Check if SQL starts with a write keyword (skipping leading comments). */
  private def isWriteStatement(sql: String): Boolean =
    val upper = skipComments(sql).toUpperCase
    writePrefix.exists(p => upper.startsWith(p))

  /** Wrap a DataSource so Flyway's Statement.execute() uses executeUpdate() for write statements.
    * This works around the rqlite JDBC driver routing INSERTs containing the word "select" in
    * string literals to the read-only `/db/query` endpoint instead of `/db/execute`.
    */
  private def wrapForMigrations(ds: DataSource): DataSource =
    new DataSource:
      def getConnection(): java.sql.Connection =
        val conn = ds.getConnection()
        java
          .lang
          .reflect
          .Proxy
          .newProxyInstance(
            conn.getClass.getClassLoader,
            Array(classOf[java.sql.Connection]),
            (_, method, args) =>
              val result = method.invoke(
                conn,
                (
                  if args == null then
                    Array.empty[Object]
                  else
                    args
                )*)
              if method.getName == "createStatement" then
                val stmt = result.asInstanceOf[java.sql.Statement]
                java
                  .lang
                  .reflect
                  .Proxy
                  .newProxyInstance(
                    stmt.getClass.getClassLoader,
                    stmt.getClass.getInterfaces,
                    (_, m, a) =>
                      if m.getName == "execute" && a != null && a.length >= 1 &&
                        a(0).isInstanceOf[String] && isWriteStatement(a(0).asInstanceOf[String])
                      then
                        stmt.executeUpdate(a(0).asInstanceOf[String])
                        java.lang.Boolean.FALSE
                      else
                        m.invoke(
                          stmt,
                          (
                            if a == null then
                              Array.empty[Object]
                            else
                              a
                          )*)
                  )
                  .asInstanceOf[java.sql.Statement]
              else
                result
              end if
          )
          .asInstanceOf[java.sql.Connection]
      end getConnection
      def getConnection(u: String, p: String): java.sql.Connection = getConnection()
      def getLogWriter(): java.io.PrintWriter = ds.getLogWriter()
      def setLogWriter(out: java.io.PrintWriter): Unit = ds.setLogWriter(out)
      def setLoginTimeout(seconds: Int): Unit = ds.setLoginTimeout(seconds)
      def getLoginTimeout(): Int = ds.getLoginTimeout()
      def getParentLogger(): java.util.logging.Logger = ds.getParentLogger()
      def unwrap[T](iface: Class[T]): T = ds.unwrap(iface)
      def isWrapperFor(iface: Class[?]): Boolean = ds.isWrapperFor(iface)

  private val fly4sConfig = Fly4sConfig
    .default
    .copy(
      locations = List(Location("db/migration")),
      group = false,
      mixed = true,
      baselineOnMigrate = true,
      baselineVersion = MigrationVersion("0"))

  /** The API's own history table, and Flyway's default. Named so the MCP server can ask what
    * version this one reached without repeating the string.
    */
  val ApiHistoryTable: String = fly4sConfig.table

  /** Runs one set of migrations against one history table, with nothing else migrating at the same
    * time.
    *
    * Exposed because the MCP server has a schema of its own and must not migrate through this one —
    * two processes sharing a history table would race on startup. What it must share is the
    * DataSource wrapping above: rqlite reports a rejected statement inside an HTTP 200 and the
    * driver turns that into zero rows, so a migration run without the wrapper reports success
    * having applied nothing.
    *
    * [[Lock]] is what separate history tables do not solve. They keep the API and the backoffice
    * out of each other's way, but every location is its own ECS service running the same API image,
    * and that image migrates before it serves — so a fleet redeploy is one migrator per location
    * against one history table. Flyway's own lock cannot help, for the reason recorded on [[Lock]]:
    * there are no transactions here for it to hold one in.
    *
    * Taking it here rather than leaving it to callers is the point: this is the only way into
    * Flyway in this codebase, so there is no path that migrates unlocked.
    */
  def migrateTo(url: String, location: String, historyTable: String): Task[Int] =
    Lock(url):
      Fly4s
        .makeFor[Task](
          ZIO.attempt(wrapForMigrations(RqliteDataSource.create(url))),
          config = fly4sConfig.copy(locations = List(Location(location)), table = historyTable))
        .use(_.migrate.map(_.migrationsExecuted))

  /** A mutex across every process that might migrate at the same time.
    *
    * Private, and reachable only through [[migrateTo]]. Nothing outside should be able to take it,
    * because a lock held around anything but a migration is a lock that blocks migrations for a
    * reason no one will think to look for.
    *
    * Flyway has its own locking and it cannot work here, for the reason one file over: the rqlite
    * JDBC driver silently drops writes when `autoCommit` is false, so [[RqliteDataSource]] forces
    * it true and turns `commit` and `rollback` into no-ops. Flyway serialises migrators by holding
    * a lock on the history table *inside a transaction* — with no transaction there is no lock, and
    * two processes read the same current version and both start applying the same migration.
    *
    * Measured, not assumed. Two migrators against one fresh rqlite reported 23 and 22 migrations
    * applied: both ran, and the second's `INSERT` into the history table collided on
    * `installed_rank` only after its DDL had already gone through. `ALTER TABLE ADD COLUMN` is not
    * idempotent, so the history and the schema end up disagreeing and a human has to work out
    * which.
    *
    * Not a hypothetical either: every location is its own ECS service running the same API image,
    * and that image migrates before it serves. A fleet redeploy across twenty locations is twenty
    * migrators starting at once against one history table.
    *
    * rqlite has no lock of its own — 8.36 offers no such endpoint, and an interactive transaction
    * is not a thing its HTTP API has. What it does offer is the only primitive this needs: a single
    * statement goes through Raft, so of N concurrent `INSERT`s of one primary key exactly one takes
    * effect and every node agrees which.
    */
  private object Lock:

    private val Table = "migration_lock"

    /** Always 1. The table holds at most one row and the primary key on it is the mutex. */
    private val Row = 1

    private val PollInterval = 2.seconds

    /** How long to wait for the holder before giving up.
      *
      * Generous, because what it is waiting for is a schema migration and the alternative to
      * waiting is serving against a schema that is halfway through changing.
      */
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

    /** Insert, then read back who actually holds it.
      *
      * The read-back is the answer, not the insert's outcome, and that is the whole lesson of the
      * first version of this: it decided by whether the `INSERT` threw, and rqlite reports a
      * rejected statement *inside an HTTP 200* which the driver turns into zero affected rows
      * rather than an exception. Both migrators were told they had won. The same trap is recorded
      * on `wrapForMigrations` above; it caught this code too.
      *
      * Reading back is immune to all of it — it does not care whether the driver throws, returns
      * zero, or returns one, and the read is linearizable because it goes through Raft. So the
      * insert is only a bid, and any failure from it is discarded: if something is genuinely wrong,
      * the read that follows fails and *that* propagates.
      */
    private def tryAcquire(url: String, holder: String): Task[Either[String, Boolean]] =
      for
        // Both the count and the exception, because the driver uses each for a different failure
        // and neither on its own is the answer. A rejected statement comes back as zero affected
        // rows *inside an HTTP 200* — no exception at all — which is the trap recorded on
        // `wrapForMigrations` above and the one that broke the first two versions of this.
        bid  <- update(url, s"INSERT INTO $Table (id, holder) VALUES ($Row, '$holder')").either
        held <- holderOf(url)
      yield
        if held.contains(holder) then
          Right(true)
        else if held.nonEmpty then
          // Someone else got there first, which is the ordinary case and not a problem.
          Right(false)
        else
          // Nothing holds it and the bid did not land, so waiting would never end. Reported
          // instead of retried, because it fails the same way every time: the first version of
          // this table carried a `beat_at NOT NULL` column, and against a database still holding
          // that shape every insert was rejected while the read-back kept answering "free" — ten
          // minutes of "held by nobody" and then a timeout blaming a migrator that did not exist.
          Left(
            bid match
              case Left(e) =>
                Option(e.getMessage).getOrElse(e.toString)
              case Right(rows) =>
                s"the insert affected $rows rows and raised nothing, which is how this driver reports a statement the database refused")

    private def release(url: String, holder: String): Task[Unit] =
      update(url, s"DELETE FROM $Table WHERE id = $Row AND holder = '$holder'").unit

    /** Runs `effect` with nothing else migrating, or fails rather than running it alongside
      * something that is.
      *
      * Waits and then takes the lock itself rather than skipping, which is safe and is what keeps
      * this small: Flyway reads the history table and applies only what is pending, so the second
      * holder finds nothing to do and reports zero. The correctness check is Flyway's own.
      *
      * There is no heartbeat and no takeover of an abandoned lock, and that is deliberate. A
      * migrator that dies mid-migration leaves a half-applied schema *and* a failed row in Flyway's
      * history, which Flyway itself refuses to migrate past until someone repairs it — so a
      * takeover would hand the lock to a process that cannot use it, in exactly the case the
      * takeover exists for. Waiting and then saying who holds it surfaces the problem instead of
      * dressing it up.
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

    /** Who holds it, for the log line and the conditional `DELETE`.
      *
      * Narrowed to a safe alphabet rather than quoted, because every statement here is built by
      * interpolation like its neighbours in this file. A hostname cannot contain a quote in
      * practice; narrowing means it does not have to be true.
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
    * there yet.
    *
    * Read with plain JDBC rather than through Flyway: the question is what another process already
    * did, and building a Flyway instance for someone else's history would let it try to create or
    * baseline that history as a side effect of asking.
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
      // No history table means nothing has migrated yet, which is an answer rather than a failure.
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
