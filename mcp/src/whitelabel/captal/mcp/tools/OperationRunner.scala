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
import whitelabel.captal.platform.operations.{OperationQueue, RunProgressQuill}
import whitelabel.captal.platform.repositories.RunRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.interop.catz.*

/** Takes the runs [[OperationFlows.start]] queued, one at a time: walks each one's steps through
  * `steps` in `core`'s order, keeping its heartbeat fresh while it does, and ends it through
  * [[OperationFlows.complete]] as the operator who started it.
  *
  * A run interrupted by a restart is not taken up again on startup: its heartbeat goes stale, and
  * the next operation asked of the location offers to finish it.
  */
object OperationRunner:
  /** Forks the loop for the life of the given `Scope`. Building it starts nothing. */
  def start(
      queue: OperationQueue,
      quill: QuillSqlite,
      steps: OperationSteps[Task],
      complete: OperationFlows.Complete,
      operators: OperatorContext): ZIO[Scope, Nothing, Unit] =
    queue
      .take
      .flatMap(run =>
        runOne(run, quill, steps, complete, operators).catchAllCause(cause =>
          ZIO.logErrorCause(s"Run ${run.asString} could not be run", cause)))
      .forever
      .forkScoped
      .unit

  private def runOne(
      id: RunId,
      quill: QuillSqlite,
      steps: OperationSteps[Task],
      complete: OperationFlows.Complete,
      operators: OperatorContext): Task[Unit] = RunRepositoryQuill(quill)
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
        for
          outcome <- ZIO
            .scoped(heartbeat.forkScoped *> Execution.run[Task](run, steps, progress))
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
