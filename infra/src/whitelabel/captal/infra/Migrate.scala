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

  /** Runs one set of migrations against one history table.
    *
    * Exposed because the MCP server has a schema of its own and must not migrate through this one —
    * two processes sharing a history table would race on startup. What it must share is the
    * DataSource wrapping above: rqlite reports a rejected statement inside an HTTP 200 and the
    * driver turns that into zero rows, so a migration run without the wrapper reports success
    * having applied nothing.
    */
  def migrateTo(url: String, location: String, historyTable: String): Task[Int] = Fly4s
    .makeFor[Task](
      ZIO.attempt(wrapForMigrations(RqliteDataSource.create(url))),
      config = fly4sConfig.copy(locations = List(Location(location)), table = historyTable))
    .use(_.migrate.map(_.migrationsExecuted))

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
