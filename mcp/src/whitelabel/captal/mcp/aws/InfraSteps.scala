package whitelabel.captal.mcp.aws

import whitelabel.captal.core.infrastructure.OperationSteps
import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.operation.{Step, StepFailure}
import whitelabel.captal.platform.aws.AssetStore
import zio.*

/** The operations' steps against AWS: ECS, the load balancer and CloudWatch through [[InfraStore]],
  * S3 and CloudFront through [[AssetStore]].
  *
  * Each step finds what it needs by the slug — the target group by its name, the task definition by
  * its family's latest revision — since finishing an interrupted run starts part way, with nothing
  * handed over from the steps before. A wait whose time runs out is a failure where the operation
  * cannot stand on it — no task of the new revision running — and a note where it can — tasks still
  * stopping, a target not yet healthy.
  */
object InfraSteps:
  /** The longest any one wait is given. Nothing holds a connection open for it, so it only has to
    * be long enough for AWS, and short enough that a container which never starts ends its run as
    * failed rather than leaving it running for ever.
    */
  val WaitAtMost: Duration = 15.minutes

  def apply(store: InfraStore, assets: AssetStore): OperationSteps[Task] =
    new OperationSteps[Task]:
      def run(slug: Slug, step: Step): Task[Either[StepFailure, Option[String]]] =
        val name = slug.asString
        val outcome: ZIO[Any, StepFailure, Option[String]] =
          step match
            case Step.CopyBundle =>
              rejected(assets.copyBundle(name)).map: copy =>
                Some(
                  if copy.objects > 0 then
                    s"${copy.objects} objects"
                  else
                    "No bundle objects: the shared bundle has never been published, so the portal page answers 404 until ci.run publishes it")
            case Step.InvalidateEdge =>
              rejected(assets.invalidatePrefix(name)).map(Some(_))
            case Step.LogGroup =>
              rejected(store.ensureLogGroup(name)).map(Some(_))
            case Step.TargetGroup =>
              rejected(store.ensureTargetGroup(name)).as(None)
            case Step.ListenerRule | Step.Forward =>
              rejected(store.ensureTargetGroup(name).flatMap(store.ensureRule(name, _))).as(None)
            case Step.TaskDefinition =>
              rejected(store.registerTaskDefinition(name)).map(arn => Some(revisionOf(arn)))
            case Step.Service =>
              rejected(
                for
                  targetGroup <- store.ensureTargetGroup(name)
                  revision    <- store.latestRevision(name)
                  _           <- store.upsertService(name, revision, targetGroup)
                yield ()).as(None)
            case Step.AwaitRevisionRunning =>
              rejected(store.latestRevision(name)).flatMap: revision =>
                rejected(store.awaitRevisionRunning(name, revision, WaitAtMost)).flatMap: running =>
                  if running then
                    ZIO.none
                  else
                    ZIO.fail(
                      StepFailure.TimedOut(
                        s"No task of ${revisionOf(revision)} was running after ${WaitAtMost
                            .toMinutes} minutes. ECS is still trying; ${store
                            .targets
                            .logGroup(name)} says why the container does not start."))
            case Step.AwaitRevisionSettled =>
              rejected(store.latestRevision(name)).flatMap: revision =>
                rejected(store.awaitRevisionSettled(name, revision, WaitAtMost)).flatMap:
                  case Some("COMPLETED") =>
                    ZIO.none
                  case Some(state) =>
                    ZIO.fail(
                      StepFailure.Rejected(
                        s"ECS ended the rollout of ${revisionOf(revision)} as $state. ${store
                            .targets
                            .logGroup(name)} says why."))
                  case None =>
                    ZIO.fail(
                      StepFailure.TimedOut(
                        s"${revisionOf(revision)} was still rolling out after ${WaitAtMost
                            .toMinutes} minutes: its task runs but has not passed the load balancer's health checks. ECS is still trying; ${store
                            .targets
                            .logGroup(name)} says why."))
            case Step.MaintenancePage =>
              rejected(assets.pausePage(name)).as(None)
            case Step.AwaitEdgeDropsPage | Step.AwaitEdgeDropsMaintenance =>
              timedOut(assets.invalidateAndWait(name, WaitAtMost)).map(Some(_))
            case Step.AnswerPaused =>
              rejected(store.pauseRule(name)).as(None)
            case Step.ScaleToZero =>
              // No live service is not a failure: there is nothing to stop.
              store.scaleService(name, 0).fold(_ => Some("No live service to scale"), _ => None)
            case Step.AwaitDrained =>
              rejected(store.awaitRunning(name, _ == 0, WaitAtMost)).map(done =>
                Option.unless(done)("Still stopping when the wait ran out"))
            case Step.ScaleToOne =>
              rejected(store.scaleService(name, 1)).as(None)
            case Step.AwaitTaskRunning =>
              rejected(store.awaitRunning(name, _ >= 1, WaitAtMost)).flatMap: running =>
                if running then
                  ZIO.none
                else
                  ZIO.fail(
                    StepFailure.TimedOut(
                      s"Scaled the service back up, but no task was running after ${WaitAtMost
                          .toMinutes} minutes, so the rule still answers 'paused' and the edge still serves the maintenance page, which is the safe end of a container that will not start. ${store
                          .targets
                          .logGroup(name)} says why."))
            case Step.AwaitHealthyTarget =>
              rejected(store.awaitHealthyTarget(name, WaitAtMost)).map(healthy =>
                Option.unless(healthy)("Not yet healthy when the wait ran out"))
            case Step.RestorePage =>
              rejected(assets.resumePage(name)).map(found =>
                Option.unless(found)("No saved page was found"))
            case Step.Teardown =>
              rejected(store.destroy(name)).map: report =>
                Some(
                  s"Service ${
                      if report.serviceDeleted then
                        "deleted"
                      else
                        "already gone"
                    }, ${report.rulesDeleted} rule(s), target group ${
                      if report.targetGroupDeleted then
                        "deleted"
                      else
                        "already gone"
                    }, ${report.taskDefinitionsDeregistered} revision(s); ${report.logGroup} kept")
            case Step.DeleteAssets =>
              rejected(
                assets.deletePrefix(s"$name/") <*
                  assets.deletePrefix(AssetStore.savedPageKey(name))).map(count =>
                Some(s"$count objects"))
            case Step.KeepAssets =>
              ZIO.none
        outcome.either
      end run

  private def rejected[A](effect: ZIO[Any, String, A]): ZIO[Any, StepFailure, A] = effect.mapError(
    StepFailure.Rejected(_))

  private def timedOut[A](effect: ZIO[Any, String, A]): ZIO[Any, StepFailure, A] = effect.mapError(
    StepFailure.TimedOut(_))

  /** `captal-<slug>:<n>`, the part of a task definition ARN a person reads. */
  private def revisionOf(taskDefinitionArn: String): String = taskDefinitionArn.split('/').last
end InfraSteps
