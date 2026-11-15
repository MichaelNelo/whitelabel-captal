package whitelabel.captal.core.operation

import java.time.Instant

import whitelabel.captal.core.location.{LocationId, Slug}

enum Event:
  /** The run `run` of `operation` on the location starts, with `steps` to take — every step of the
    * operation, or only those left by the run it finishes. The location does not change: it changes
    * when the run completes.
    *
    * @param supersedes
    *   the run that may still hold the location's lock: one started whose runner died, which never
    *   released it. None when the lock is free, as a completed or failed run leaves it.
    */
  case OperationStarted(
      run: RunId,
      location: LocationId,
      slug: Slug,
      operation: Operation,
      steps: List[Step],
      finishing: Option[RunId],
      supersedes: Option[RunId],
      by: String,
      occurredAt: Instant)

  /** Every step done; the location's own event, its new state, comes with it. */
  case OperationCompleted(run: RunId, location: LocationId, occurredAt: Instant)

  /** Stopped at `step`. The location stays where it was. */
  case OperationFailed(
      run: RunId,
      location: LocationId,
      step: Step,
      failure: StepFailure,
      occurredAt: Instant)
end Event
