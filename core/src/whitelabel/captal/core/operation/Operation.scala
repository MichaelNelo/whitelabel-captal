package whitelabel.captal.core.operation

import cats.data.NonEmptyList

/** What can be done to a location's infrastructure, each run as a [[Run]] of fixed [[Step]]s. */
enum Operation:
  case Deploy
  case Pause
  case Resume

  /** Removes what the location has in AWS, and its S3 objects too when `deleteAssets`. */
  case Destroy(deleteAssets: Boolean)

  /** How the run records it, and how it reads in a sentence: "deploy", "pause"… */
  def name: String =
    this match
      case Deploy =>
        "deploy"
      case Pause =>
        "pause"
      case Resume =>
        "resume"
      case Destroy(_) =>
        "destroy"
end Operation

object Operation:
  /** The operation's steps, in the order that is a domain rule. A pause swaps the location's page
    * and waits for the edge to drop it before the API answers "paused" and the service scales down:
    * an API that refuses behind a page that still offers the flow strands the guest. A resume goes
    * the other way, the API back before the page. A deploy stands up what the service needs before
    * the service, and copies the page first.
    *
    * Every step can be run twice with the same result, since finishing an interrupted run runs
    * again the step it died in, which may already have happened.
    */
  def steps(operation: Operation): NonEmptyList[Step] =
    operation match
      case Deploy =>
        NonEmptyList.of(
          Step.CopyBundle,
          Step.InvalidateEdge,
          Step.LogGroup,
          Step.TargetGroup,
          Step.ListenerRule,
          Step.TaskDefinition,
          Step.Service,
          Step.AwaitRevisionRunning,
          Step.AwaitRevisionSettled
        )
      case Pause =>
        NonEmptyList.of(
          Step.MaintenancePage,
          Step.AwaitEdgeDropsPage,
          Step.AnswerPaused,
          Step.ScaleToZero,
          Step.AwaitDrained)
      case Resume =>
        NonEmptyList.of(
          Step.TargetGroup,
          Step.ScaleToOne,
          Step.AwaitTaskRunning,
          Step.Forward,
          Step.AwaitHealthyTarget,
          Step.RestorePage,
          Step.AwaitEdgeDropsMaintenance)
      case Destroy(deleteAssets) =>
        NonEmptyList.of(
          Step.ScaleToZero,
          Step.AwaitDrained,
          Step.Teardown,
          if deleteAssets then
            Step.DeleteAssets
          else
            Step.KeepAssets)
end Operation

/** One thing an operation does to the infrastructure. The same step can belong to more than one
  * operation — a pause and a destroy both scale the service to zero — and does the same in each.
  */
enum Step(val description: String):
  case CopyBundle                extends Step("Copy the shared bundle under the location's prefix")
  case InvalidateEdge            extends Step("Invalidate the location's prefix at the edge")
  case LogGroup                  extends Step("Log group")
  case TargetGroup               extends Step("Target group")
  case ListenerRule              extends Step("Listener rule")
  case TaskDefinition            extends Step("Task definition revision")
  case Service                   extends Step("ECS service")
  case AwaitRevisionRunning      extends Step("A task of the new revision running")
  case AwaitRevisionSettled      extends Step("The service settled on the new revision")
  case MaintenancePage           extends Step("Swap the location's page for the maintenance page")
  case AwaitEdgeDropsPage        extends Step("Wait for the edge to drop the location's page")
  case AnswerPaused              extends Step("Answer 'paused' from the load balancer")
  case ScaleToZero               extends Step("Scale the service to zero")
  case AwaitDrained              extends Step("Wait for the running tasks to stop")
  case ScaleToOne                extends Step("Scale the service to one task")
  case AwaitTaskRunning          extends Step("Wait for a task to run")
  case Forward                   extends Step("Forward to the target group again")
  case AwaitHealthyTarget        extends Step("Wait for a healthy target")
  case RestorePage               extends Step("Put the location's page back")
  case AwaitEdgeDropsMaintenance extends Step("Wait for the edge to drop the maintenance page")
  case Teardown
      extends Step(
        "Remove the service, its rules, target group, task definitions and change feed queues")
  case DeleteAssets extends Step("Delete the location's S3 objects")
  case KeepAssets   extends Step("Keep the location's S3 objects")
end Step

/** Why a step did not do what it does, as the port that ran it reports it: never an AWS string, but
  * what an operator acts on.
  */
enum StepFailure:
  /** What it waited for did not happen in time; AWS may still be at it. */
  case TimedOut(detail: String)

  /** AWS refused it, or what it needed was not there. */
  case Rejected(detail: String)

  def detail: String

/** How a run's steps ended: all of them done, or the first that failed. */
enum Outcome:
  case Succeeded
  case Failed(step: Step, failure: StepFailure)
