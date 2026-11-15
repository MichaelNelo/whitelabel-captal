package whitelabel.captal.platform.queries

import java.time.Instant

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{Query, RunReport, RunStatusQuery, StepReport}
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.core.operation.Run
import whitelabel.captal.platform.RunRow
import whitelabel.captal.platform.operations.RunRecords
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** [[RunStatusQuery]], read from `mcp_deploy_runs`. A run started whose heartbeat went stale is
  * reported as interrupted, which is never stored.
  */
object RunStatus:
  def apply(quill: QuillSqlite): Query.Aux[Task, RunStatusQuery, RunReport] =
    new Query[Task, RunStatusQuery]:
      type Result = RunReport

      def execute(q: RunStatusQuery): Task[Either[Error, RunReport]] =
        import quill.*
        val found: Task[Either[LocationError, RunRow]] =
          q match
            case RunStatusQuery.ById(run) =>
              quill
                .run(query[RunRow].filter(_.id == lift(run)))
                .map(_.headOption.toRight(LocationError.NoSuchRun(run)))
            case RunStatusQuery.LatestOf(slug) =>
              Slug.parse(slug) match
                case Left(invalid) =>
                  ZIO.succeed(Left(invalid))
                case Right(parsed) =>
                  quill
                    .run(query[RunRow].filter(_.slug == lift(parsed.asString)))
                    .map(
                      _.maxByOption(row => Instant.parse(row.startedAt))
                        .toRight(LocationError.NoRuns(parsed)))
        for
          row <- found
          now <- Clock.instant
        yield row.left.map(error => Error.Location(NonEmptyChain.one(error))).map(reportOf(_, now))
      end execute

  private def reportOf(row: RunRow, now: Instant): RunReport =
    val stale = Instant.parse(row.heartbeatAt).plus(Run.StaleAfter).isBefore(now)
    RunReport(
      id = row.id,
      slug = row.slug,
      operation = row.operation,
      status =
        if row.status == RunRecords.Running && stale then
          RunRecords.Interrupted
        else
          row.status
      ,
      steps = RunRecords.steps(row).map(entry => StepReport(entry.name, entry.state, entry.detail)),
      startedBy = row.startedBy,
      startedAt = row.startedAt,
      updatedAt = row.heartbeatAt,
      finishedAt = row.finishedAt,
      error = row.error
    )
end RunStatus
