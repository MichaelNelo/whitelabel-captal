package whitelabel.captal.core.operation

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import whitelabel.captal.core.infrastructure.{OperationSteps, RunProgress}

object Execution:
  /** The run's pending steps, in order, through `steps`, each recorded on `progress` as it starts
    * and ends, stopping at the first that fails. What [[Operation.steps]] orders, this keeps to:
    * here the order is tested against a fake port, without AWS.
    *
    * A step and its record are one: once started, nothing from outside — the server stopping, say —
    * cuts it before it is recorded as ended, so what the run says was done is what was done. It can
    * be stopped between two steps only, before the next one is recorded as started.
    */
  def run[F[_]](run: Run[RunState.Started], steps: OperationSteps[F], progress: RunProgress[F])(
      using F: MonadCancelThrow[F]): F[Outcome] =
    run
      .pending
      .foldLeftM[F, Outcome](Outcome.Succeeded):
        case (Outcome.Succeeded, step) =>
          F.uncancelable: _ =>
            for
              _      <- progress.started(run.id, step)
              result <- steps.run(run.slug, step)
              _      <- progress.finished(run.id, step, result)
            yield result.fold(Outcome.Failed(step, _), _ => Outcome.Succeeded)
        case (failed, _) =>
          failed.pure[F]
end Execution
