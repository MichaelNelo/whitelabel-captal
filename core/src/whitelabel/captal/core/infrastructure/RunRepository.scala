package whitelabel.captal.core.infrastructure

import java.time.Instant

import whitelabel.captal.core.location.{LocationId, Slug}
import whitelabel.captal.core.operation.{Run, RunId, RunState, Step, StepFailure}

trait RunRepository[F[_]]:
  /** The location's latest run, in whatever state, which the commands match on. */
  def latest(location: LocationId): F[Option[Run[RunState]]]

  /** The run with this id, if it is going. */
  def findStarted(run: RunId): F[Option[Run[RunState.Started]]]

/** What runs a step against the infrastructure: the backoffice's AWS clients, or a fake in a test.
  * Answers the step's note for the operator when it did what it does — a revision, a count, "still
  * stopping when the wait ran out" — and why when it did not.
  *
  * Each step finds what it needs by the location's slug rather than taking it from the step before:
  * finishing an interrupted run starts part way, with nothing handed over.
  */
trait OperationSteps[F[_]]:
  def run(slug: Slug, step: Step): F[Either[StepFailure, Option[String]]]

/** Where a run's progress is kept for whoever follows it: each step as it starts and ends, and the
  * heartbeat that says somebody is still at it. Written as it happens rather than as events: none
  * of it is a fact of the domain, and the run's start and end are.
  */
trait RunProgress[F[_]]:
  def started(run: RunId, step: Step): F[Unit]
  def finished(run: RunId, step: Step, result: Either[StepFailure, Option[String]]): F[Unit]
  def heartbeat(run: RunId, at: Instant): F[Unit]
