package whitelabel.captal.infra

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import zio.*

/** DataSource wrapper for rqlite that forces autoCommit=true on all connections. rqlite's JDBC
  * driver silently drops writes when autoCommit=false, so we intercept setAutoCommit(), commit(),
  * and rollback() calls to keep autoCommit=true always.
  */
object RqliteDataSource:

  private def wrapConnection(conn: java.sql.Connection): java.sql.Connection = java
    .lang
    .reflect
    .Proxy
    .newProxyInstance(
      conn.getClass.getClassLoader,
      Array(classOf[java.sql.Connection]),
      (_, method, args) =>
        if method.getName == "setAutoCommit" then null
        else if method.getName == "commit" || method.getName == "rollback" then null
        else method.invoke(conn, (if args == null then Array.empty[Object] else args)*)
    )
    .asInstanceOf[java.sql.Connection]

  def create(url: String): DataSource =
    new DataSource:
      def getConnection(): java.sql.Connection =
        wrapConnection(java.sql.DriverManager.getConnection(url))
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
