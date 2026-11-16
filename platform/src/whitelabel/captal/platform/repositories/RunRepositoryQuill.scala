package whitelabel.captal.platform.repositories

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.infrastructure.RunRepository
import whitelabel.captal.core.location.LocationId
import whitelabel.captal.core.operation.{Run, RunId, RunState}
import whitelabel.captal.platform.locks.DistributedLock
import whitelabel.captal.platform.operations.{OperationLock, RunRecords}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRow, RunRow}
import zio.*

/** A location's runs as `mcp_deploy_runs` holds them, found by the location's slug, which is
  * permanent; whether one is going, as its location's lock in Redis says ([[OperationLock]]). The
  * lock lasts as long as a runner keeps extending it, so a lock held is an operation going, and a
  * run not completed with no lock held is one nobody is at any more.
  */
object RunRepositoryQuill:
  def apply(quill: QuillSqlite, locks: DistributedLock): RunRepository[Task] =
    import quill.*
    new RunRepository[Task]:
      def going(location: LocationId): Task[Option[RunId]] = locks
        .holder(OperationLock.resource(location))
        .map(_.map(RunId(_)))

      def unfinished(location: LocationId): Task[Option[Run[RunState]]] = going(location).flatMap:
        case Some(_) =>
          ZIO.none
        case None =>
          latest(location).map(
            _.filter:
              case Run(_, _, _, _, _, _, RunState.Completed(_)) =>
                false
              case _ =>
                true)

      def findStarted(id: RunId): Task[Option[Run[RunState.Started]]] = run(
        for
          row <- query[RunRow].filter(r =>
            r.id == lift(id.asString) && r.status == lift(RunRecords.Running))
          location <- query[LocationRow].join(_.slug == row.slug)
        yield (row, location.id))
        .map(_.headOption.map((row, location) => RunRecords.runOf(row, LocationId(location))))
        .orDie
        .map(
          _.collect:
            case run @ Run(_, _, _, _, _, _, started: RunState.Started) =>
              run.copy(state = started))

      /** The one started last. Sorted after parsing rather than in SQL: the instants are stored as
        * written, with as many fraction digits as they had, which does not sort as text.
        */
      private def latest(location: LocationId): Task[Option[Run[RunState]]] =
        run(query[LocationRow].filter(_.id == lift(location.asString)).map(_.slug))
          .flatMap:
            _.headOption match
              case None =>
                ZIO.none
              case Some(slug) =>
                run(query[RunRow].filter(_.slug == lift(slug))).map(
                  _.maxByOption(row => Instant.parse(row.startedAt))
                    .map(RunRecords.runOf(_, location)))
          .orDie
    end new
  end apply
end RunRepositoryQuill
