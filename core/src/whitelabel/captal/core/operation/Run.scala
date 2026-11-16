package whitelabel.captal.core.operation

import java.time.{Duration, Instant}

import whitelabel.captal.core.location.{LocationId, Slug}

/** Where a run stands. */
enum RunState:
  /** Running, as far as anyone knows: its runner keeps `heartbeat` fresh while it works. */
  case Started(heartbeat: Instant)
  case Completed(at: Instant)

  /** Stopped at `step`, which did not do what it does. */
  case Failed(step: Step, failure: StepFailure)

/** One run of an operation on a location, by its state.
  *
  * @param pending
  *   the steps not done yet, in order: all of them when it starts, the ones after the last done
  *   while it runs — the step it is at included, since it may not have finished.
  * @param startedBy
  *   the operator token that started it, under which its runner completes it.
  */
final case class Run[S <: RunState](
    id: RunId,
    location: LocationId,
    slug: Slug,
    operation: Operation,
    pending: List[Step],
    startedBy: String,
    state: S)

/** Whether a run is going is the run repository's to say ([[RunRepository.going]]); its heartbeat
  * says only, to whoever follows it, that it was reporting in a moment ago.
  */
object Run:
  /** How often a runner refreshes its run's heartbeat. */
  val HeartbeatEvery: Duration = Duration.ofSeconds(10)

  /** A heartbeat older than this is shown as a run interrupted. */
  val StaleAfter: Duration = Duration.ofMinutes(1)
