package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.commands.CompleteOperationCommand
import whitelabel.captal.core.application.queries.DispatchQuery
import whitelabel.captal.core.infrastructure.{OperationSteps, RunProgress, RunRepository}
import whitelabel.captal.core.operation.{
  Execution,
  Outcome,
  Run,
  RunId,
  RunState,
  Step,
  StepFailure
}
import whitelabel.captal.mcp.aws.DispatchRules
import whitelabel.captal.platform.locks.{DistributedLock, Lease}
import whitelabel.captal.platform.operations.{OperationLock, OperationQueue, RunProgressQuill}
import whitelabel.captal.platform.repositories.RunRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.interop.catz.*

/** Takes the runs [[OperationFlows.start]] queued, each on a fiber of its own so that one
  * location's long deploy keeps no other waiting: walks the run's steps through `steps` in `core`'s
  * order, keeping its heartbeat fresh while it does, and ends it through
  * [[OperationFlows.complete]] as the operator who started it.
  *
  * The location's lock, taken by the start, is kept for as long as the run goes
  * ([[DistributedLock.keep]]) and let go once it has ended. The walk races it: once the lock is
  * lost — it expired while this process could not reach Redis, and another operation may have taken
  * it — the walk stops between two steps, since a step and its record are never cut part way
  * ([[Execution.run]]), and the run ends failed. Failed even when its last step was done: another
  * operation may have moved the location meanwhile, so a run that lost its lock never moves it.
  *
  * A run interrupted by a restart is not taken up again on startup: its lock expires, and the next
  * operation asked of the location offers to finish it.
  */
object OperationRunner:
  /** Forks the loop for the life of the given `Scope`. Building it starts nothing. Once a run has
    * ended, its location's dispatch rules are written through `dispatch`: a deploy, pause, resume
    * or destroy changes where its access points' guests go.
    */
  def start(
      queue: OperationQueue,
      quill: QuillSqlite,
      steps: OperationSteps[Task],
      complete: OperationFlows.Complete,
      operators: OperatorContext,
      locks: DistributedLock,
      dispatch: DispatchRules): ZIO[Scope, Nothing, Unit] =
    queue
      .take
      .flatMap(run =>
        runOne(run, quill, steps, complete, operators, locks, dispatch)
          .catchAllCause(cause => ZIO.logErrorCause(s"Run ${run.asString} could not be run", cause))
          .forkScoped)
      .forever
      .forkScoped
      .unit

  private val LockLost = StepFailure.Rejected(
    "The location's lock was lost while this ran, so it stopped here: another operation may have taken over the location.")

  private def runOne(
      id: RunId,
      quill: QuillSqlite,
      steps: OperationSteps[Task],
      complete: OperationFlows.Complete,
      operators: OperatorContext,
      locks: DistributedLock,
      dispatch: DispatchRules): Task[Unit] =
    val runs = RunRepositoryQuill(quill, locks)
    runs
      .findStarted(id)
      .flatMap:
        case None =>
          ZIO.logWarning(s"Run ${id.asString} was queued but is not going")
        case Some(run) =>
          val progress = RunProgressQuill(quill)
          val heartbeat = Clock
            .instant
            .flatMap(progress.heartbeat(run.id, _))
            .repeat(Schedule.spaced(Duration.fromJava(Run.HeartbeatEvery)))
          ZIO.scoped:
            for
              outcome <- locks
                .keep(OperationLock.resource(run.location), run.id.asString, OperationLock.Ttl)
                .foldZIO(
                  _ => ZIO.succeed(lost(run.pending.headOption)),
                  lease => heartbeat.forkScoped *> walk(run, steps, progress, lease, runs))
              now <- Clock.instant
              _   <-
                operators.locally(Operator(run.startedBy, None))(
                  complete.execute(CompleteOperationCommand(run.id, outcome, now)))
              // Still holding the location's lock, so no other operation moves it meanwhile.
              _ <- dispatch.write(DispatchQuery.OfLocation(run.location))
            yield ()
  end runOne

  /** The run's steps, raced against the loss of its lock. Something the port did not answer for —
    * the database, a defect — ends the run failed at the step it had reached rather than leaving it
    * going for ever; past its last step, every step was done.
    */
  private def walk(
      run: Run[RunState.Started],
      steps: OperationSteps[Task],
      progress: RunProgress[Task],
      lease: Lease,
      runs: RunRepository[Task]): UIO[Outcome] = Execution
    .run(run, steps, progress)
    .map(Some(_))
    .raceFirst(lease.lost.as(None))
    .foldCauseZIO(
      cause =>
        reached(runs, run).map(_.fold(Outcome.Succeeded)(Outcome.Failed(_, failureOf(cause)))),
      {
        case Some(outcome) =>
          ZIO.succeed(outcome)
        // The race waited for the step going to end and be recorded, so what was reached is read
        // as it stands.
        case None =>
          reached(runs, run).map(step => lost(step.orElse(run.pending.lastOption)))
      }
    )

  /** A run that lost its lock, failed at `step`: none only for a run with no step to take. */
  private def lost(step: Option[Step]): Outcome =
    step.fold(Outcome.Succeeded)(Outcome.Failed(_, LockLost))

  /** The first step the run had not done, read again since the walk may have moved past the start.
    */
  private def reached(runs: RunRepository[Task], run: Run[RunState.Started]): UIO[Option[Step]] =
    runs.findStarted(run.id).map(_.fold(run.pending.headOption)(_.pending.headOption)).orDie

  private def failureOf(cause: Cause[Throwable]): StepFailure = StepFailure.Rejected(
    Option(cause.squash.getMessage).getOrElse(cause.squash.toString))
end OperationRunner
