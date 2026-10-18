package whitelabel.captal.platform

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import zio.*

/** The DataSource everything here goes through. A plain one now.
  *
  * Every connection used to be wrapped in a reflection proxy that forced `autoCommit` true and
  * turned `commit` and `rollback` into no-ops, because `rqlite-jdbc` 9.1.2.1 silently dropped
  * writes with `autoCommit` false. Two driver versions later that is fixed, and measured rather
  * than assumed: against a 10.6.1 server, `setAutoCommit(false)` sticks and a row inserted and then
  * rolled back is not there afterwards.
  *
  * Removing it is not tidying. The proxy was disabling the only transaction in this codebase:
  * [[eventhandlers.TransactionalEventHandler]] wraps a batch of event handlers in
  * `quill.transaction` and logs "transaction rolled back" on failure — and with `commit` and
  * `rollback` intercepted, nothing was ever transactional and nothing ever rolled back. A batch
  * that failed halfway left an answer persisted without the phase that follows it, while the log
  * said otherwise. `AGENTS.md` documents that atomicity as a design decision; until now it was not
  * true.
  *
  * It also means a migration is atomic. Flyway's own lock is still not available — it says so in
  * its logs, "SQLite does not support locking, no concurrent migration supported" — so
  * [[Migrate.Lock]] is what serialises migrators either way.
  */
object RqliteDataSource:

  def create(url: String): DataSource =
    new DataSource:
      def getConnection(): java.sql.Connection = java.sql.DriverManager.getConnection(url)
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
