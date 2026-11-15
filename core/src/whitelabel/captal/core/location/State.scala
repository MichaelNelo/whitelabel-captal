package whitelabel.captal.core.location

import cats.data.NonEmptyList
import whitelabel.captal.core.operation.RunId

/** Where a location stands, each state an operation that succeeded. One in progress is its run's
  * state, not the location's: until it completes, nothing has happened to the location.
  */
enum State:
  /** Created, and never deployed. */
  case Created

  /** Deployed, or resumed, by the run `by`, taking guests through `accessPoints`: a deployed
    * location with none would turn every guest away.
    */
  case Deployed(by: RunId, accessPoints: NonEmptyList[AccessPoint])

  /** Paused by the run `by`: its page is the maintenance page, and its access points are free for
    * another location to take guests through.
    */
  case Paused(by: RunId)

  /** Its infrastructure destroyed by the run `by`. Not an end: the location keeps its row and its
    * content, and a deploy stands it up again.
    */
  case Destroyed(by: RunId)
end State
