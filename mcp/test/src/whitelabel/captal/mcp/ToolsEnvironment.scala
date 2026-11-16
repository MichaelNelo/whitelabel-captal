package whitelabel.captal.mcp

import io.getquill.jdbczio.Quill
import whitelabel.captal.mcp.tools.{ContentFlows, LocationFlows, Queries}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{OperatorContext as Operators, RqliteDataSource}
import zio.*

/** What the specs that run tools against rqlite provide them. */
object ToolsEnvironment:
  /** The database, the location's and the content's flows with the operator context they log under,
    * and the queries that read the database alone, as `Main` composes them. Built by hand rather
    * than wired, since the flows' refined types meet in one intersection that the wiring macro
    * cannot split.
    */
  val withFlows: ZLayer[
    Any,
    Throwable,
    QuillSqlite & Operators & LocationFlows.All & ContentFlows.All & Queries.Database] =
    (RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)) >>>
      ZLayer.scopedEnvironment:
        for
          quill     <- ZIO.service[QuillSqlite]
          operators <- Operators.make.build.map(_.get)
        yield ZEnvironment[QuillSqlite, Operators](quill, operators) ++
          LocationFlows.environment(quill, operators) ++
          ContentFlows.environment(quill, operators) ++ Queries.database(quill)
end ToolsEnvironment
