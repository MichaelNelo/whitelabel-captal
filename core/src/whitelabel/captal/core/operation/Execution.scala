package whitelabel.captal.core.operation

import cats.Monad
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import whitelabel.captal.core.infrastructure.{OperationSteps, RunProgress}

object Execution:
  /** The run's pending steps, in order, through `steps`, each recorded on `progress` as it starts
    * and ends, stopping at the first that fails. What [[Operation.steps]] orders, this keeps to:
    * here the order is tested against a fake port, without AWS.
    */
  def run[F[_]: Monad](
      run: Run[RunState.Started],
      steps: OperationSteps[F],
      progress: RunProgress[F]): F[Outcome] =
    run
      .pending
      .foldLeftM[F, Outcome](Outcome.Succeeded):
        case (Outcome.Succeeded, step) =>
          for
            _      <- progress.started(run.id, step)
            result <- steps.run(run.slug, step)
            _      <- progress.finished(run.id, step, result)
          yield result.fold(Outcome.Failed(step, _), _ => Outcome.Succeeded)
        case (failed, _) =>
          failed.pure[F]
end Execution
