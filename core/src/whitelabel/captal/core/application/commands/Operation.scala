package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{
  AccessPointRepository,
  LocationRepository,
  RunRepository,
  TermsRepository
}
import whitelabel.captal.core.location.{
  Error as LocationError,
  Event as LocationEvent,
  Location,
  Slug,
  State
}
import whitelabel.captal.core.operation.{
  Event as OperationEvent,
  Operation,
  Outcome,
  Run,
  RunId,
  RunState
}

/** Starts the operation `operation` on the location `slug` as the run `newRun`, started by the
  * operator token `by` — or, with `finishing`, finishes the unfinished run it names, taking only
  * the steps it left.
  *
  * @param holder
  *   the run that holds the location's lock, which the caller tried to take for `newRun` before
  *   running this: none when it took it. Taking it is I/O, so it happens outside, and its outcome
  *   comes in as data, like the instant and the new id.
  */
final case class StartOperationCommand(
    slug: Slug,
    operation: Operation,
    finishing: Option[RunId],
    newRun: RunId,
    holder: Option[RunId],
    by: String,
    occurredAt: Instant)

/** Decides whether the operation may start, and with which steps. In order:
  *
  *   - another run holds the location's lock: [[LocationError.OperationInProgress]];
  *   - with the lock this run's, nobody is at the latest run, so one started and not completed was
  *     left part way, as is one that failed. Unless this command finishes it:
  *     [[LocationError.PreviousOperationUnfinished]] — unless it is a destroy, which removes
  *     whatever is there, half changed or not;
  *   - the state the location is in, which the operation has to leave: a deploy of a deployed one
  *     is [[LocationError.AlreadyDeployed]], anything else out of place
  *     [[LocationError.NotAllowedFrom]]. A destroy leaves every state, since whatever stands in AWS
  *     — a deploy that failed part way included — is what it removes;
  *   - for a deploy or a resume, which take guests: the terms of service in force, an access point
  *     at least, and none of them taking another deployed location's guests.
  *
  * The location does not change: it changes when the run completes ([[CompleteOperationHandler]]).
  */
object StartOperationHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F],
      accessPoints: AccessPointRepository[F],
      runs: RunRepository[F],
      terms: TermsRepository[F]): Handler.Aux[F, StartOperationCommand, Unit] =
    new Handler[F, StartOperationCommand]:
      type Result = Unit

      def handle(cmd: StartOperationCommand): F[Op[Unit]] = locations
        .find(cmd.slug)
        .flatMap:
          case None =>
            Monad[F].pure(refused(LocationError.NotFound(cmd.slug)))
          case Some(location) =>
            runs
              .latest(location.id)
              .flatMap: latest =>
                (cmd.holder, latest) match
                  case (Some(holder), _) =>
                    val going = latest.filter(_.id == holder)
                    Monad[F].pure(
                      refused(
                        LocationError.OperationInProgress(
                          holder,
                          going.fold(cmd.operation)(_.operation),
                          going.flatMap(_.pending.headOption))))
                  case (None, Some(run))
                      if leftPartWay(run) && !cmd.finishing.contains(run.id) &&
                        !destroys(cmd.operation) =>
                    Monad[F].pure(
                      refused(
                        LocationError.PreviousOperationUnfinished(
                          run.id,
                          run.operation,
                          run.pending)))
                  case _ =>
                    val finishing = latest.filter(run =>
                      cmd.finishing.contains(run.id) && leftPartWay(run))
                    val operation = finishing.fold(cmd.operation)(_.operation)
                    from(location, operation) match
                      case Some(error) =>
                        Monad[F].pure(refused(error))
                      case None =>
                        conditions(location, operation).map:
                          case Some(errors) =>
                            CoreOp.fail[Event, Error, Unit](Error.Location(errors))
                          case None =>
                            CoreOp.emit[Event, Error](
                              Event.Operation(
                                OperationEvent.OperationStarted(
                                  cmd.newRun,
                                  location.id,
                                  location.slug,
                                  operation,
                                  finishing.fold(Operation.steps(operation).toList)(_.pending),
                                  finishing.map(_.id),
                                  // A started run whose runner died never released the lock.
                                  latest.collect:
                                    case run @ Run(_, _, _, _, _, _, RunState.Started(_)) =>
                                      run.id
                                  ,
                                  cmd.by,
                                  cmd.occurredAt
                                )))
                    end match
                end match

      /** Not completed: with the lock this command's, nobody is at a started run any more. */
      private def leftPartWay(run: Run[RunState]): Boolean =
        run.state match
          case RunState.Started(_) | RunState.Failed(_, _) =>
            true
          case RunState.Completed(_) =>
            false

      private def destroys(operation: Operation): Boolean =
        operation match
          case Operation.Destroy(_) =>
            true
          case _ =>
            false

      /** Refused when `operation` does not leave the state `location` is in. */
      private def from(location: Location[State], operation: Operation): Option[LocationError] =
        (location.state, operation) match
          case (_: State.Deployed, Operation.Deploy) =>
            Some(LocationError.AlreadyDeployed(location.slug))
          case (State.Created | _: State.Destroyed, Operation.Deploy) =>
            None
          case (_: State.Deployed, Operation.Pause) =>
            None
          case (_: State.Paused, Operation.Resume) =>
            None
          // From any state: a deploy that failed part way leaves the location created, or still
          // destroyed, with resources standing in AWS, and a destroy is what removes them.
          case (_, Operation.Destroy(_)) =>
            None
          case (state, _) =>
            Some(LocationError.NotAllowedFrom(nameOf(state), operation))

      private def nameOf(state: State): String =
        state match
          case State.Created =>
            "created"
          case _: State.Deployed =>
            "deployed"
          case _: State.Paused =>
            "paused"
          case _: State.Destroyed =>
            "destroyed"

      /** What a deploy or a resume needs, which take guests from their access points on. */
      private def conditions(
          location: Location[State],
          operation: Operation): F[Option[NonEmptyChain[LocationError]]] =
        operation match
          case Operation.Deploy | Operation.Resume =>
            for
              version <- terms.current
              points  <- accessPoints.of(location.id)
              held    <- points.traverse: point =>
                accessPoints
                  .deployedHolders(point.mac)
                  .map(_.find(_ != location.slug).map(LocationError.AccessPointHeld(point.mac, _)))
            yield NonEmptyChain.fromSeq(
              Option.when(version.isEmpty)(LocationError.NoTermsOfService).toList ++
                Option.when(points.isEmpty)(LocationError.NoAccessPoint).toList ++ held.flatten)
          case Operation.Pause | Operation.Destroy(_) =>
            Monad[F].pure(None)
end StartOperationHandler

/** Ends the run `run` as `outcome` says. */
final case class CompleteOperationCommand(run: RunId, outcome: Outcome, occurredAt: Instant)

/** The run, which knows its operation, ends: done, with the location's transition to the state the
  * operation leads to; or failed at a step, with the location where it was. The location is still
  * where the operation found it, since the lock lets no other operation in between.
  */
object CompleteOperationHandler:
  def apply[F[_]: Monad](runs: RunRepository[F]): Handler.Aux[F, CompleteOperationCommand, Unit] =
    new Handler[F, CompleteOperationCommand]:
      type Result = Unit

      def handle(cmd: CompleteOperationCommand): F[Op[Unit]] = runs
        .findStarted(cmd.run)
        .map:
          case None =>
            refused(LocationError.RunNotStarted(cmd.run))
          case Some(run) =>
            cmd.outcome match
              case Outcome.Failed(step, failure) =>
                CoreOp.emit[Event, Error](
                  Event.Operation(
                    OperationEvent.OperationFailed(
                      run.id,
                      run.location,
                      step,
                      failure,
                      cmd.occurredAt)))
              case Outcome.Succeeded =>
                CoreOp.emitAll[Event, Error, Unit](
                  List(
                    Event.Location(transition(run, cmd.occurredAt)),
                    Event.Operation(
                      OperationEvent.OperationCompleted(run.id, run.location, cmd.occurredAt))),
                  ())

      private def transition(run: Run[RunState.Started], at: Instant): LocationEvent =
        run.operation match
          case Operation.Deploy =>
            LocationEvent.LocationDeployed(run.location, run.id, at)
          case Operation.Pause =>
            LocationEvent.LocationPaused(run.location, run.id, at)
          case Operation.Resume =>
            LocationEvent.LocationResumed(run.location, run.id, at)
          case Operation.Destroy(_) =>
            LocationEvent.LocationDestroyed(run.location, run.id, at)
end CompleteOperationHandler

private def refused(error: LocationError): Op[Unit] = CoreOp.fail[Event, Error, Unit](
  Error.Location(NonEmptyChain.one(error)))
