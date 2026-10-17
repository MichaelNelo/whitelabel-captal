package whitelabel.captal.infra

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import zio.*

/** The DataSource everything here goes through, with transactions held off.
  *
  * The original reason is gone. This forced `autoCommit` true because `rqlite-jdbc` 9.1.2.1
  * silently dropped writes with it false; on 10.5.2.1 that is fixed — measured against a 10.6.1
  * server, `setAutoCommit(false)` sticks and a row inserted then rolled back is not there
  * afterwards. Transactions work.
  *
  * It stays, but no longer for a reason anyone has measured. Turning transactions on made migration
  * V21 fail, and the first reading of that was "the transaction is the trigger" — reached by
  * running the *old* driver against the new server and seeing it pass. That measurement was
  * worthless: the old driver reports a refused statement as zero affected rows inside an HTTP 200,
  * so it passed by not seeing the failure. The real cause was SQLite 3.53 refusing to drop a column
  * an index still references, which V21 now handles itself.
  *
  * So whether transactions can be turned on here is open and worth measuring, and the measurement
  * is cheap: remove the proxy and run the suite. What it would buy is migrations that are atomic
  * rather than statement-by-statement. What it will not buy is Flyway's own lock — Flyway says so
  * in its logs, "SQLite does not support locking, no concurrent migration supported" — so
  * `Migrate.Lock` is needed either way.
  */
object RqliteDataSource:

  /** Forces `autoCommit` true and makes `commit` and `rollback` no-ops.
    *
    * The unwrapping is the part that matters and the part the previous version of this got wrong.
    * `Method.invoke` reports whatever the target threw as an `InvocationTargetException`, which no
    * JDBC interface declares — so a proxy that lets it escape turns every real `SQLException` into
    * an `UndeclaredThrowableException` with the cause stripped. That was harmless while the driver
    * threw nothing and became the top failure the moment it did: the end-to-end suite reported
    * `UndeclaredThrowableException` from inside Flyway and said nothing about what the database had
    * refused.
    */
  private def wrapConnection(conn: java.sql.Connection): java.sql.Connection = java
    .lang
    .reflect
    .Proxy
    .newProxyInstance(
      conn.getClass.getClassLoader,
      Array(classOf[java.sql.Connection]),
      (_, method, args) =>
        if method.getName == "setAutoCommit" || method.getName == "commit" ||
          method.getName == "rollback" then
          null
        else
          try
            method.invoke(
              conn,
              (
                if args == null then
                  Array.empty[Object]
                else
                  args
              )*)
          catch
            case e: java.lang.reflect.InvocationTargetException =>
              throw e.getCause
    )
    .asInstanceOf[java.sql.Connection]

  def create(url: String): DataSource =
    new DataSource:
      def getConnection(): java.sql.Connection = wrapConnection(
        java.sql.DriverManager.getConnection(url))
      def getConnection(u: String, p: String): java.sql.Connection = getConnection()
      def getLogWriter(): java.io.PrintWriter = null
      def setLogWriter(out: java.io.PrintWriter): Unit = ()
      def setLoginTimeout(seconds: Int): Unit = ()
      def getLoginTimeout(): Int = 0
      def getParentLogger(): java.util.logging.Logger = java.util.logging.Logger.getGlobal()
      def unwrap[T](iface: Class[T]): T = throw new java.sql.SQLException("Not a wrapper")
      def isWrapperFor(iface: Class[?]): Boolean = false

  val layer: ZLayer[Any, Throwable, DataSource] = ZLayer.fromZIO:
    ZIO.attempt:
      val url = ConfigFactory.load().getString("database.jdbcUrl")
      create(url)
end RqliteDataSource
