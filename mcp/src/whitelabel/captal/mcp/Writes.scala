package whitelabel.captal.mcp

import zio.ZIO

/** Guard every write goes through.
  *
  * rqlite reports a rejected statement as an `error` field inside an HTTP 200 response, and the
  * JDBC driver turns that into "0 rows affected" rather than an exception — the same class of
  * silent drop `RqliteDataSource` already works around for `autoCommit=false`. Without checking the
  * count, a write the database refused is indistinguishable from one it accepted.
  *
  * The consequence is broader than one helper: constraints in this stack are not a safety net.
  * Uniqueness and referential integrity have to be enforced in Scala before writing, and the count
  * checked after. `I18nToolsSpec` pins the driver behaviour with executable evidence.
  */
object Writes:
  def expectOne(affected: Long, what: String): ZIO[Any, String, Unit] =
    ZIO
      .when(affected != 1)(
        ZIO.fail(s"$what affected $affected rows, expected 1. The database rejected it silently."))
      .unit

  def expectAtLeastOne(affected: Long, what: String): ZIO[Any, String, Unit] =
    ZIO.when(affected < 1)(ZIO.fail(s"$what matched no rows. Nothing was changed.")).unit
end Writes
