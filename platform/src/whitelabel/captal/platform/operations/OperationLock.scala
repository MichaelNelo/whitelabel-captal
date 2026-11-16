package whitelabel.captal.platform.operations

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.location.LocationId
import whitelabel.captal.core.operation.{Event as OperationEvent, RunId}
import whitelabel.captal.platform.locks.{Acquired, DistributedLock, LockUnavailable}
import zio.*

/** The lock that keeps a location to one operation at a time: taken as a run starts ([[taking]]),
  * kept by the runner while it works ([[DistributedLock.keep]]), and let go once the run ends.
  * Whether one is going is read from it too
  * ([[whitelabel.captal.platform.repositories.RunRepositoryQuill]]).
  */
object OperationLock:
  /** Long enough for an answer to come back through a slow moment, short enough that a backoffice
    * that died frees its locations soon after.
    */
  val Ttl: Duration = 30.seconds

  def resource(location: LocationId): String = s"captal:location:${location.asString}:operation"

  /** Takes the location's lock for a run that starts before `inner` writes it, and keeps it for the
    * runner once `inner` is done; lets it go if `inner` fails, so a start that wrote nothing holds
    * nothing. A location another run took since the command read it fails the start with
    * [[TransitionTaken]]. Events that start no run go through as they are.
    */
  def taking(locks: DistributedLock)(inner: EventHandler[Task, Event]): EventHandler[Task, Event] =
    events =>
      events.collectFirst { case Event.Operation(started: OperationEvent.OperationStarted) =>
        started
      } match
        case None =>
          inner.handle(events)
        case Some(started) =>
          val lock = resource(started.location)
          val token = started.run.asString
          ZIO.acquireReleaseExitWith(locks.acquire(lock, token, Ttl))((acquired, exit) =>
            ZIO.when(acquired.isInstanceOf[Acquired.Held] && exit.isFailure)(
              locks.release(lock, token))):
            case Acquired.Held(_) =>
              inner.handle(events)
            case Acquired.HeldBy(holder) =>
              ZIO.fail(TransitionTaken(started.run, RunId(holder)))
            case Acquired.Unanswered =>
              ZIO.fail(LockUnavailable(lock))
end OperationLock

/** Another run took the location between the command's reading it free and the start taking it:
  * `holder` holds it now.
  */
final case class TransitionTaken(run: RunId, holder: RunId)
    extends Exception(
      s"Run ${run.asString} did not start: run ${holder.asString} took the location first")
