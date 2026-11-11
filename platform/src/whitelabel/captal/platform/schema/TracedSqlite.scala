package whitelabel.captal.platform.schema

import java.sql.SQLException
import javax.sql.DataSource

import io.getquill.context.ExecutionInfo
import io.getquill.jdbczio.Quill
import io.getquill.{ReturnAction, SnakeCase}
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.SpanKind
import zio.*
import whitelabel.captal.telemetry.Tracing

/** The database context every module queries through, with each statement in a span of its own: a
  * client span, the child of whatever asked, carrying the statement Quill sent. rqlite emits no
  * traces itself, so this is where the database shows in one. The statement's values are bound
  * parameters, never in its text, so none of them reaches a span.
  */
final class TracedSqlite(dataSource: DataSource, tracing: Tracing)
    extends Quill.Sqlite[SnakeCase](SnakeCase, dataSource):

  private def traced[A](sql: String)(statement: ZIO[Any, SQLException, A]) =
    tracing.span(TracedSqlite.name(sql), SpanKind.CLIENT, TracedSqlite.attributes(sql))(statement)

  override def executeQuery[T](sql: String, prepare: Prepare, extractor: Extractor[T])(
      info: ExecutionInfo,
      dc: Runner): ZIO[Any, SQLException, List[T]] =
    traced(sql)(super.executeQuery(sql, prepare, extractor)(info, dc))

  override def executeQuerySingle[T](sql: String, prepare: Prepare, extractor: Extractor[T])(
      info: ExecutionInfo,
      dc: Runner): ZIO[Any, SQLException, T] =
    traced(sql)(super.executeQuerySingle(sql, prepare, extractor)(info, dc))

  override def executeAction(sql: String, prepare: Prepare)(
      info: ExecutionInfo,
      dc: Runner): ZIO[Any, SQLException, Long] =
    traced(sql)(super.executeAction(sql, prepare)(info, dc))

  override def executeActionReturning[O](
      sql: String,
      prepare: Prepare,
      extractor: Extractor[O],
      returningBehavior: ReturnAction)(info: ExecutionInfo, dc: Runner): ZIO[Any, SQLException, O] =
    traced(sql)(super.executeActionReturning(sql, prepare, extractor, returningBehavior)(info, dc))

  override def executeActionReturningMany[O](
      sql: String,
      prepare: Prepare,
      extractor: Extractor[O],
      returningBehavior: ReturnAction)(
      info: ExecutionInfo,
      dc: Runner): ZIO[Any, SQLException, List[O]] =
    traced(sql)(
      super.executeActionReturningMany(sql, prepare, extractor, returningBehavior)(info, dc))

  override def executeBatchAction(groups: List[BatchGroup])(
      info: ExecutionInfo,
      dc: Runner): ZIO[Any, SQLException, List[Long]] =
    traced(groups.map(_.string).mkString("; "))(super.executeBatchAction(groups)(info, dc))

  override def executeBatchActionReturning[T](
      groups: List[BatchGroupReturning],
      extractor: Extractor[T])(info: ExecutionInfo, dc: Runner): ZIO[Any, SQLException, List[T]] =
    traced(groups.map(_.string).mkString("; "))(
      super.executeBatchActionReturning(groups, extractor)(info, dc))

  /** The statements inside are its children. */
  override def transaction[R, A](op: ZIO[R, Throwable, A]): ZIO[R, Throwable, A] =
    tracing.span("transaction", SpanKind.INTERNAL, TracedSqlite.System)(super.transaction(op))
end TracedSqlite

object TracedSqlite:
  val layer: URLayer[DataSource & Tracing, QuillSqlite] = ZLayer.fromFunction(
    (dataSource: DataSource, tracing: Tracing) =>
      new TracedSqlite(dataSource, tracing): QuillSqlite)

  private val System = Attributes.of(AttributeKey.stringKey("db.system.name"), "rqlite")

  /** The table a statement names after `FROM`, `INTO` or `UPDATE`: the first, for a join. */
  private val Table = """(?i)\b(?:from|into|update)\s+["`]?(\w+)""".r.unanchored

  private def operation(sql: String) = sql.trim.takeWhile(!_.isWhitespace).toUpperCase

  private def table(sql: String) =
    sql match
      case Table(name) =>
        Some(name)
      case _ =>
        None

  /** `SELECT users`, as OpenTelemetry names a database span: the operation, then its target. */
  private def name(sql: String) = (operation(sql) :: table(sql).toList).mkString(" ")

  private def attributes(sql: String) =
    val builder = Attributes
      .builder()
      .putAll(System)
      .put(AttributeKey.stringKey("db.operation.name"), operation(sql))
      .put(AttributeKey.stringKey("db.query.text"), sql)
    table(sql).foreach(builder.put(AttributeKey.stringKey("db.collection.name"), _))
    builder.build()
end TracedSqlite
