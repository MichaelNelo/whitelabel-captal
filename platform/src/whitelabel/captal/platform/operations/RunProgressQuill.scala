package whitelabel.captal.platform.operations

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.infrastructure.RunProgress
import whitelabel.captal.core.operation.{RunId, Step, StepFailure}
import whitelabel.captal.platform.RunRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** A run's progress on its row, written as it happens: each step's state and note, and the
  * heartbeat. One writer per run, its runner, so rewriting the whole step list loses nobody else's
  * update.
  */
object RunProgressQuill:
  def apply(quill: QuillSqlite): RunProgress[Task] =
    import quill.*
    new RunProgress[Task]:
      def started(run: RunId, step: Step): Task[Unit] = mark(
        run,
        step,
        RunRecords.StepRunning,
        None)

      def finished(
          run: RunId,
          step: Step,
          result: Either[StepFailure, Option[String]]): Task[Unit] =
        result match
          case Right(note) =>
            mark(run, step, RunRecords.StepDone, note)
          case Left(failure) =>
            mark(run, step, RunRecords.StepFailed, Some(failure.detail))

      def heartbeat(run: RunId, at: Instant): Task[Unit] =
        quill
          .run(
            query[RunRow]
              .filter(_.id == lift(run.asString))
              .update(_.heartbeatAt -> lift(at.toString)))
          .unit

      private def mark(run: RunId, step: Step, state: String, detail: Option[String]): Task[Unit] =
        for
          rows <- quill.run(query[RunRow].filter(_.id == lift(run.asString)))
          row  <- ZIO
            .fromOption(rows.headOption)
            .orElseFail(IllegalStateException(s"Run ${run.asString} is not recorded"))
          now <- Clock.instant
          updated = RunRecords
            .steps(row)
            .map(entry =>
              if entry.name == step.description then
                entry.copy(state = state, detail = detail)
              else
                entry)
          _ <- quill.run(
            query[RunRow]
              .filter(_.id == lift(run.asString))
              .update(
                _.steps       -> lift(RunRecords.encode(updated)),
                _.heartbeatAt -> lift(now.toString)))
        yield ()
    end new
  end apply
end RunProgressQuill
