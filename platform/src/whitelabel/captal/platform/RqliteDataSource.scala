package whitelabel.captal.platform

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import zio.*

/** The DataSource everything here goes through. Connections are plain: the driver honours
  * `setAutoCommit(false)`, so `quill.transaction` and a migration really are atomic. Flyway's own
  * lock is still unavailable ("SQLite does not support locking, no concurrent migration supported")
  * — [[Migrate.Lock]] is what serialises migrators.
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
