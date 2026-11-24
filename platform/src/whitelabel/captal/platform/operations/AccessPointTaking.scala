package whitelabel.captal.platform.operations

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.{Error as AppError, Event, EventHandler, Flow}
import whitelabel.captal.core.infrastructure.AccessPointRepository
import whitelabel.captal.core.location.{Error as LocationError, LocationId, Slug}
import whitelabel.captal.core.operation.{Event as OperationEvent, Operation, RunId}
import whitelabel.captal.platform.eventhandlers.DbEventHandler
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{ActiveAccessPointRow, ActiveAccessPoints}
import zio.*

/** The access points a deploy or a resume takes as it starts, in `active_access_points`: every one
  * its location is configured with, `deploying`, written in the transaction that writes the run. An
  * access point another location has already taken fails the commit on the table's primary key,
  * whatever the timing, since rqlite applies writes one after the other; [[refusing]] turns that
  * into the refusal an operator reads.
  *
  * The run ends them: failed, its `deploying` rows are deleted here; succeeded, the location's
  * transition holds them ([[whitelabel.captal.platform.eventhandlers.LocationPersistenceHandler]]).
  * A run that stops part way keeps them until it is finished, or the location destroyed.
  */
object AccessPointTaking:
  def handler(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Operation(started: OperationEvent.OperationStarted) if takes(started) =>
            take(quill, started.location, started.run, started.occurredAt.toString)
          case Event.Operation(OperationEvent.OperationFailed(failed, _, _, _, _)) =>
            run(
              query[ActiveAccessPointRow]
                .filter(row =>
                  row.runId == lift(failed.asString) &&
                    row.state == lift(ActiveAccessPoints.Deploying))
                .delete).unit
          case _ =>
            ZIO.unit

  /** A start whose commit failed because another location took one of its location's access points
    * first fails instead with [[LocationError.AccessPointHeld]] for each, read once the commit
    * failed; any other failure goes through as it is. The commit's failure is a defect, not an
    * error: Quill commits in the transaction's finalizer, inside `ZIO.succeed`.
    */
  def refusing(accessPoints: AccessPointRepository[Task])(
      inner: EventHandler[Task, Event]): EventHandler[Task, Event] =
    events =>
      events.collectFirst {
        case Event.Operation(started: OperationEvent.OperationStarted) if takes(started) =>
          started
      } match
        case None =>
          inner.handle(events)
        case Some(started) =>
          inner
            .handle(events)
            .catchAllCause: cause =>
              if cause.isInterruptedOnly then
                ZIO.refailCause(cause)
              else
                held(accessPoints, started.location, started.slug).flatMap(found =>
                  NonEmptyChain.fromSeq(found) match
                    case Some(errors) =>
                      ZIO.fail(Flow.HandlerError(NonEmptyChain.one(AppError.Location(errors))))
                    case None =>
                      ZIO.refailCause(cause))

  private def takes(started: OperationEvent.OperationStarted): Boolean =
    started.operation match
      case Operation.Deploy | Operation.Resume =>
        true
      case Operation.Pause | Operation.Destroy(_) =>
        false

  /** The location's own rows go first: a run left unfinished had written them. */
  private def take(quill: QuillSqlite, location: LocationId, by: RunId, at: String): Task[Unit] =
    import quill.*
    val id = location.asString
    val runId = by.asString
    run(query[ActiveAccessPointRow].filter(_.locationId == lift(id)).delete) *>
      run(quote(sql"""INSERT INTO active_access_points (access_point_id, location_id, state, run_id, since)
                SELECT access_point_id, location_id, ${lift(ActiveAccessPoints.Deploying)},
                       ${lift(runId)}, ${lift(at)}
                FROM location_access_points
                WHERE location_id = ${lift(id)}""".as[Insert[ActiveAccessPointRow]])).unit

  private def held(
      accessPoints: AccessPointRepository[Task],
      location: LocationId,
      slug: Slug): Task[List[LocationError]] = accessPoints
    .of(location)
    .flatMap(
      ZIO.foreach(_)(point =>
        accessPoints
          .takenBy(point.mac)
          .map(_.filter(_ != slug).map(LocationError.AccessPointHeld(point.mac, _)))))
    .map(_.flatten)
end AccessPointTaking
