package whitelabel.captal.platform.operations

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.location.LocationId
import whitelabel.captal.core.operation.Event as OperationEvent
import whitelabel.captal.platform.locks.DistributedLock
import zio.*

/** The lock that keeps a location to one operation at a time: taken by the tool that starts a run,
  * under the run's id, kept by the runner while it works, and let go once the run ends.
  */
object OperationLock:
  /** Long enough for an answer to come back through a slow moment, short enough that a backoffice
    * that died frees its locations soon after.
    */
  val Ttl: Duration = 30.seconds

  /** How often a runner extends the lock of the run it is working: three times within its time. */
  val ExtendEvery: Duration = 10.seconds

  def resource(location: LocationId): String = s"captal:location:${location.asString}:operation"

  /** After the commit that ended a run: its location's lock goes, if the run still holds it. */
  def releasing(locks: DistributedLock): EventHandler[Task, Event] =
    events =>
      ZIO.foreachDiscard(events):
        case Event.Operation(OperationEvent.OperationCompleted(run, location, _)) =>
          locks.release(resource(location), run.asString)
        case Event.Operation(OperationEvent.OperationFailed(run, location, _, _, _)) =>
          locks.release(resource(location), run.asString)
        case _ =>
          ZIO.unit
end OperationLock
