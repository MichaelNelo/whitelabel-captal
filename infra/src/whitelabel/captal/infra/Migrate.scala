package whitelabel.captal.infra

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import zio.*
import zio.interop.catz.*

object Migrate extends ZIOAppDefault:

  private val writePrefix =
    Set("INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "REPLACE", "PRAGMA")

  /** Check if SQL starts with a write keyword (skipping leading comments). */
  private def isWriteStatement(sql: String): Boolean =
    val trimmed = sql.stripLeading()
    val withoutComments =
      if trimmed.startsWith("--") then trimmed.dropWhile(_ != '\n').stripLeading()
      else if trimmed.startsWith("/*") then
        val end = trimmed.indexOf("*/")
        if end >= 0 then trimmed.drop(end + 2).stripLeading() else trimmed
      else trimmed
    val upper = withoutComments.toUpperCase
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
              val result = method.invoke(conn, (if args == null then Array.empty[Object] else args)*)
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
                      if m.getName == "execute" && a != null && a.length >= 1 && a(0)
                          .isInstanceOf[String] && isWriteStatement(a(0).asInstanceOf[String])
                      then
                        stmt.executeUpdate(a(0).asInstanceOf[String])
                        java.lang.Boolean.FALSE
                      else m.invoke(stmt, (if a == null then Array.empty[Object] else a)*)
                  )
                  .asInstanceOf[java.sql.Statement]
              else result
          )
          .asInstanceOf[java.sql.Connection]
      def getConnection(u: String, p: String): java.sql.Connection = getConnection()
      def getLogWriter(): java.io.PrintWriter = ds.getLogWriter()
      def setLogWriter(out: java.io.PrintWriter): Unit = ds.setLogWriter(out)
      def setLoginTimeout(seconds: Int): Unit = ds.setLoginTimeout(seconds)
      def getLoginTimeout(): Int = ds.getLoginTimeout()
      def getParentLogger(): java.util.logging.Logger = ds.getParentLogger()
      def unwrap[T](iface: Class[T]): T = ds.unwrap(iface)
      def isWrapperFor(iface: Class[?]): Boolean = ds.isWrapperFor(iface)

  private def fly4sConfig(devSeed: Boolean) =
    val locations =
      if devSeed then List(Location("db/migration"), Location("db/migration-dev"))
      else List(Location("db/migration"))
    Fly4sConfig.default.copy(
      locations = locations,
      group = false,
      mixed = true,
      baselineOnMigrate = true,
      baselineVersion = MigrationVersion("0"))

  override val run: ZIO[Any, Throwable, Unit] = ZIO
    .attempt:
      val config = ConfigFactory.load()
      val url = config.getString("database.jdbcUrl")
      val devSeed = config.getBoolean("database.dev-seed")
      (url, devSeed)
    .flatMap: (url, devSeed) =>
      Fly4s
        .makeFor[Task](
          ZIO.attempt(wrapForMigrations(RqliteDataSource.create(url))),
          config = fly4sConfig(devSeed))
        .use: fly4s =>
          for
            _ <- ZIO.logInfo(
              s"Running database migrations...${if devSeed then " (with dev seeds)" else ""}")
            result <- fly4s.migrate
            _      <- ZIO.logInfo(s"Migrations complete: ${result.migrationsExecuted} executed")
          yield ()
