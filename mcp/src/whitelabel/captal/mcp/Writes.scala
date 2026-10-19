package whitelabel.captal.mcp

import zio.ZIO

/** Guard every write goes through: rqlite reports a rejected statement as an `error` field inside
  * an HTTP 200 and the JDBC driver turns that into "0 rows affected" rather than an exception, so
  * an unchecked count cannot tell a refused write from an accepted one.
  *
  * Constraints in this stack are therefore not a safety net. Uniqueness and referential integrity
  * have to be enforced in Scala before writing, and the affected count checked after.
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
