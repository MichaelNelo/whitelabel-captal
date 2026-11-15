package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.commands.CompleteOperationCommand
import whitelabel.captal.core.infrastructure.OperationSteps
import whitelabel.captal.core.operation.{
  Execution,
  Outcome,
  Run,
  RunId,
  RunState,
  Step,
  StepFailure
}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.platform.locks.DistributedLock
import whitelabel.captal.platform.operations.{OperationLock, OperationQueue, RunProgressQuill}
import whitelabel.captal.platform.repositories.RunRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.interop.catz.*

/** Takes the runs [[OperationFlows.start]] queued, each on a fiber of its own so that one
  * location's long deploy keeps no other waiting: walks the run's steps through `steps` in `core`'s
  * order, keeping its heartbeat fresh and its location's lock extended while it does, and ends it
  * through [[OperationFlows.complete]] as the operator who started it.
  *
  * The lock is the run's from the call that started it ([[OperationLock]]). Once it cannot be
  * extended — it expired while this process could not reach Redis, and another operation may have
  * taken it — no further step runs: the run fails at the next one rather than act on AWS for a
  * location it no longer holds.
  *
  * A run interrupted by a restart is not taken up again on startup: its lock expires, and the next
  * operation asked of the location offers to finish it.
  */
object OperationRunner:
  /** Forks the loop for the life of the given `Scope`. Building it starts nothing. */
  def start(
      queue: OperationQueue,
      quill: QuillSqlite,
      steps: OperationSteps[Task],
      complete: OperationFlows.Complete,
      operators: OperatorContext,
      locks: DistributedLock): ZIO[Scope, Nothing, Unit] =
    queue
      .take
      .flatMap(run =>
        runOne(run, quill, steps, complete, operators, locks)
          .catchAllCause(cause => ZIO.logErrorCause(s"Run ${run.asString} could not be run", cause))
          .forkScoped)
      .forever
      .forkScoped
      .unit

  private def runOne(
      id: RunId,
      quill: QuillSqlite,
      steps: OperationSteps[Task],
      complete: OperationFlows.Complete,
      operators: OperatorContext,
      locks: DistributedLock): Task[Unit] = RunRepositoryQuill(quill)
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
        val resource = OperationLock.resource(run.location)
        for
          held <- Ref.make(true)
          // Lost once is lost: a lock that expired may already be another run's.
          extending = locks
            .extend(resource, run.id.asString, OperationLock.Ttl)
            .tap(extended =>
              ZIO.unless(extended)(
                held.set(false) *>
                  ZIO.logWarning(s"Run ${run.id.asString} lost the lock of ${run.slug.asString}")))
            .repeat(
              Schedule.spaced(OperationLock.ExtendEvery) && Schedule.recurWhile[Boolean](identity))
          guarded =
            new OperationSteps[Task]:
              def run(slug: Slug, step: Step): Task[Either[StepFailure, Option[String]]] = held
                .get
                .flatMap:
                  if _ then
                    steps.run(slug, step)
                  else
                    ZIO.succeed(
                      Left(
                        StepFailure.Rejected(
                          "The location's lock was lost while this ran, so it stopped here: another operation may have taken over the location.")))
          outcome <- ZIO
            .scoped(
              heartbeat.forkScoped *> extending.forkScoped *>
                Execution.run[Task](run, guarded, progress))
            // Something the port did not answer for — the database, a defect — ends the run as
            // failed at the step it had reached rather than leaving it going for ever; past its
            // last step, every step was done.
            .catchAllCause(cause =>
              reached(quill, run).map(
                _.fold(Outcome.Succeeded)(Outcome.Failed(_, failureOf(cause)))))
          now <- Clock.instant
          _   <-
            operators.locally(Operator(run.startedBy, None))(
              complete.execute(CompleteOperationCommand(run.id, outcome, now)))
        yield ()
        end for

  /** The first step the run had not done, read again since the walk may have moved past the start.
    */
  private def reached(quill: QuillSqlite, run: Run[RunState.Started]): UIO[Option[Step]] =
    RunRepositoryQuill(quill)
      .findStarted(run.id)
      .map(_.fold(run.pending.headOption)(_.pending.headOption))
      .orDie

  private def failureOf(cause: Cause[Throwable]): StepFailure = StepFailure.Rejected(
    Option(cause.squash.getMessage).getOrElse(cause.squash.toString))
end OperationRunner
