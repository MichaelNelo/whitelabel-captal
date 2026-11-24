package whitelabel.captal.core.location

import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.operation.{Operation, RunId, Step}

enum Error:
  /** Not lowercase kebab-case, or blank. */
  case InvalidSlug(slug: String)
  case BlankName

  /** Another location, named `by`, has the slug. */
  case SlugTaken(slug: Slug, by: String)
  case NotFound(slug: Slug)

  /** Not an address the load balancer's redirect can send a guest to, for `problem`. */
  case InvalidRedirectUrl(url: String, problem: RedirectTarget.Problem)

  /** Not a whole number of minutes from 1 up. */
  case InvalidDuration(minutes: Int)
  case BlankHost
  case BlankSiteId
  case InvalidPort(port: Int)

  case NoSuchAccessPoint(mac: Mac)

  /** The location already has an access point with this MAC. */
  case AccessPointExists(mac: Mac)

  /** The location `by` has taken this access point, deploying or deployed: one access point leads
    * to one location at a time, which is what the dispatch counts on.
    */
  case AccessPointHeld(mac: Mac, by: Slug)

  /** The system has no access point with this MAC yet, and nothing says which controller it answers
    * to.
    */
  case ControllerRequired(mac: Mac)

  /** The access point is the system's already and answers to `controller`: it is configured as it
    * is, and its controller is changed for every location with `update_access_point`.
    */
  case AccessPointAnswersTo(mac: Mac, controller: ControllerId)

  case NoSuchController(id: ControllerId)

  /** The controller `existing` is already at this host, port and site: chosen, not made again. */
  case ControllerExists(existing: ControllerId)

  /** `accessPoints` access points a location is configured with answer to the controller, which
    * goes only once none does.
    */
  case ControllerInUse(id: ControllerId, accessPoints: Int)

  /** The location is deployed and this is its only access point: without it every guest would be
    * turned away.
    */
  case LastAccessPoint(mac: Mac)

  /** A deploy of a location already deployed, which has nothing to do: versions reach every
    * location through the release, never through a deploy.
    */
  case AlreadyDeployed(slug: Slug)

  /** The operation does not leave the state the location is in: a resume of one not paused. */
  case NotAllowedFrom(state: String, operation: Operation)

  /** A portal asks every guest to accept the terms of service before the flow begins, so one with
    * no version to accept could not start anyone.
    */
  case NoTermsOfService

  /** Deploying or resuming a location with no access point would turn every guest away. */
  case NoAccessPoint

  /** Its run `run` of `operation` is going, at `step`. */
  case OperationInProgress(run: RunId, operation: Operation, step: Option[Step])

  /** Its run `run` of `operation` was left part way, with `pending` still to take: finished first,
    * or the location stands half changed in the infrastructure.
    */
  case PreviousOperationUnfinished(run: RunId, operation: Operation, pending: List[Step])

  /** Completing the run `run`, which is not going: completed already, or never started. */
  case RunNotStarted(run: RunId)

  /** No run has the id `run`. */
  case NoSuchRun(run: String)

  /** Nothing has been run on the location yet. */
  case NoRuns(slug: Slug)

  /** A stylesheet with nothing in it, which would not take the portal back to its base theme but
    * leave it unstyled.
    */
  case BlankStylesheet

  /** An icon with nothing in it, which every screen would show as a broken image. */
  case BlankIcon
end Error
