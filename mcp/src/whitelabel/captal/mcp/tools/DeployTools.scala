package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.mcp.apps.{RunRoutes, RunTicket, RunUi}
import whitelabel.captal.mcp.aws.{AssetStore, BundleCopy, InfraStore, Runs}
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.json.ast.Json
import zio.schema.annotation.description
import zio.schema.codec.JsonCodec as SchemaJsonCodec
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** Deploy, pause, resume and destroy a location's AWS infrastructure, and follow them.
  *
  * The four wait minutes on AWS, longer than a host keeps one tool call open, so each answers at
  * once with a run and leaves the work going in the background; `get_deploy_status` reads it back.
  */
object DeployTools:

  /** The longest any one wait inside a run is given. Nothing holds a connection open for it, so it
    * only has to be long enough for AWS, and short enough that a container which never starts ends
    * its run as failed rather than leaving it running for ever.
    */
  private val WaitAtMost: Duration = 15.minutes

  final case class DeployRequest(
      @description(
        "Which location to stand up. It has to exist — call create_location first, since the slug is what the service, the target group and the ALB rule are all named after.")
      slug: String)

  final case class DeployResponse(
      @description("The location deployed.")
      slug: String,
      @description("Log group the task writes to.")
      logGroup: String,
      @description("Target group the listener forwards to.")
      targetGroupArn: String,
      @description("The listener rule matching this location's API path.")
      ruleArn: String,
      @description("The revision registered for this deploy. Every deploy registers a new one.")
      taskDefinitionArn: String,
      @description("The ECS service running the portal.")
      serviceArn: String,
      @description("True when the service was created by this deploy, false when it was updated.")
      created: Boolean,
      @description(
        "How many bundle objects were copied under this location's prefix. Zero means the shared bundle has never been published, and the portal page itself will 404 — publish it with the client.publishS3 Mill task.")
      bundleObjects: Int,
      @description(
        "True when the location already had its own brand icon and the bundle's default was left out of the copy. False means it now has the default, which is the ordinary case for a location with no branding of its own.")
      iconPreserved: Boolean,
      @description("The CloudFront invalidation covering this location's prefix.")
      invalidation: String,
      @description(
        "The path CloudFront forwards to the ALB and the API answers on. The slug is baked into the routes at boot, so one service serves exactly this prefix.")
      pathPattern: String,
      @description("What is left.")
      nextSteps: List[String])

  given Schema[DeployRequest] = DeriveSchema.gen
  given Schema[DeployResponse] = DeriveSchema.gen

  private inline def locationBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  /** Requires location row to exist (resources named after slug). */
  private def requireLocation(slug: String): ZIO[QuillSqlite, String, LocationRow] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(locationBySlugQuery(lift(slug)))
        .orDie
        .map(_.headOption)
        .someOrFail(
          s"No location with slug '$slug'. Call create_location first — every resource this creates is named after the slug.")

  /** The token behind the call, which a run records as the one that started it. */
  private def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("Could not tell which token made this call.")

  /** Tool declaration without store (for testing). */
  private[mcp] val declaration: McpTool = McpTool("deploy_location")
    .description(
      "Stand up a location's portal in AWS: log group, target group, ALB listener rule, task definition and ECS service, then wait for the new revision to be serving. Idempotent — a location already deployed has its service rolled onto a fresh task definition rather than a second one created. Answers at once with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDeploy)

  private object DeploySteps:
    val Bundle = "Copy the shared bundle under the location's prefix"
    val Edge = "Invalidate the location's prefix at the edge"
    val LogGroup = "Log group"
    val TargetGroup = "Target group"
    val Rule = "Listener rule"
    val TaskDefinition = "Task definition revision"
    val Service = "ECS service"
    val Running = "A task of the new revision running"
    val Settled = "The service settled on the new revision"
    val all = List(
      Bundle,
      Edge,
      LogGroup,
      TargetGroup,
      Rule,
      TaskDefinition,
      Service,
      Running,
      Settled)
  end DeploySteps

  private def deployWith(
      store: InfraStore,
      uiOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = declaration
    .handleWithContext: (in: DeployRequest, ctx: McpToolContext) =>
      for
        location <- requireLocation(in.slug)
        caller   <- callerOf(ctx)
        launched <-
          Runs.launch(in.slug, "deploy", DeploySteps.all, caller)(run =>
            deploy(store, location, run).map(encode(_)))
        view <- started(launched, caller, uiOrigin)
      yield view
    .withUi(RunUi.AppUri)

  private def deploy(
      store: InfraStore,
      location: LocationRow,
      run: Runs.Progress): ZIO[QuillSqlite & AssetStore, String, DeployResponse] =
    val slug = location.slug
    for
      assets <- ZIO.service[AssetStore]
      // Copy bundle first (before AWS resources exist).
      bundle <-
        run.noting(DeploySteps.Bundle)(assets.copyBundle(slug))(copy =>
          Some(s"${copy.objects} objects"))
      invalidation <- run.step(DeploySteps.Edge)(assets.invalidatePrefix(slug))
      logGroup     <- run.step(DeploySteps.LogGroup)(store.ensureLogGroup(slug))
      targetGroup  <- run.step(DeploySteps.TargetGroup)(store.ensureTargetGroup(slug))
      rule         <- run.step(DeploySteps.Rule)(store.ensureRule(slug, targetGroup))
      taskDef      <-
        run.noting(DeploySteps.TaskDefinition)(store.registerTaskDefinition(slug))(arn =>
          Some(revisionOf(arn)))
      service <- run.step(DeploySteps.Service)(store.upsertService(slug, taskDef, targetGroup))
      (serviceArn, created) = service
      revision              = revisionOf(taskDef)
      _ <-
        run.step(DeploySteps.Running)(
          store
            .awaitRevisionRunning(slug, taskDef, WaitAtMost)
            .filterOrFail(identity)(
              s"No task of $revision was running after ${WaitAtMost
                  .toMinutes} minutes. ECS is still trying; $logGroup says why the container does not start."))
      _ <-
        run.step(DeploySteps.Settled)(
          store
            .awaitRevisionSettled(slug, taskDef, WaitAtMost)
            .flatMap:
              case Some("COMPLETED") =>
                ZIO.unit
              case Some(state) =>
                ZIO.fail(s"ECS ended the rollout of $revision as $state. $logGroup says why.")
              case None =>
                ZIO.fail(
                  s"$revision was still rolling out after ${WaitAtMost
                      .toMinutes} minutes: its task runs but has not passed the load balancer's health checks. ECS is still trying; $logGroup says why."))
    yield DeployResponse(
      slug = slug,
      logGroup = logGroup,
      targetGroupArn = targetGroup,
      ruleArn = rule,
      taskDefinitionArn = taskDef,
      serviceArn = serviceArn,
      created = created,
      bundleObjects = bundle.objects,
      iconPreserved = bundle.iconPreserved,
      invalidation = invalidation,
      pathPattern = store.targets.pathPattern(slug),
      nextSteps =
        List(
          s"The service is serving $revision.",
          "Call get_location_readiness to see whether the content is complete — this deploy created infrastructure and wrote nothing to the database.",
          if location.isActive == 1 then
            "The location is active, so guests are being served."
          else
            "The location is retired in the database, so the portal serves nothing even though its container is healthy."
        ) ++ bundleNote(bundle)
    )
    end for
  end deploy

  /** `captal-<slug>:<n>`, the part of a task definition ARN a person reads. */
  private def revisionOf(taskDefinitionArn: String): String = taskDefinitionArn.split('/').last

  /** Note when bundle objects are zero (portal page 404s). */
  private def bundleNote(bundle: BundleCopy): List[String] =
    if bundle.objects > 0 then
      Nil
    else
      List(
        "No bundle objects were copied, because the shared bundle prefix is empty. The portal page itself will 404 until the SPA is published with the client.publishS3 Mill task; deploy again afterwards to copy it under this slug.")

  final case class PauseRequest(
      @description("Which location to take out of service. It stays deployed and keeps its data.")
      slug: String)

  final case class PauseResponse(
      @description("The location paused.")
      slug: String,
      @description(
        "True once the service is asked for no tasks. The running ones stop; nothing else about the service changes.")
      scaledToZero: Boolean,
      @description(
        "True when every task had actually stopped. False means the drain was still going when the wait ran out, which does not affect the pause — the rule stopped forwarding before any of this — but does mean a container may still be running against whatever you were about to change.")
      drained: Boolean,
      @description(
        "The listener rule now answering a fixed response instead of forwarding to the target group.")
      ruleArn: String,
      @description(
        "The CloudFront invalidation that was waited out before the API was stopped. This is what makes the redirect safe: by the time anything can be told the location is paused, the edge is already serving the maintenance page.")
      invalidation: String,
      @description(
        "Exactly what the load balancer now answers on this location's API path. The portal reads this to tell a pause from an outage and sends the guest to the maintenance page itself.")
      answersWith: String,
      @description("What is left, including what this deliberately did not do.")
      nextSteps: List[String])

  final case class ResumeResponse(
      @description("The location brought back.")
      slug: String,
      @description("The rule forwarding to the target group again.")
      ruleArn: String,
      @description("The target group it forwards to.")
      targetGroupArn: String,
      @description(
        "True when the page the pause saved was put back. False means none was found, which is the ordinary answer for a location that was not paused.")
      pageRestored: Boolean,
      @description(
        "True when the load balancer reported a healthy target. False means the task is running but the ALB had not finished health-checking it when the wait ran out — the portal answers 503 until it does.")
      healthy: Boolean,
      @description("The CloudFront invalidation that was waited out.")
      invalidation: String,
      @description("What is left.")
      nextSteps: List[String])

  final case class DestroyRequest(
      @description("Which location's infrastructure to remove.")
      slug: String,
      @description(
        "The slug again, typed out. This is the confirmation: it has to match exactly, and the call is refused otherwise.")
      confirm: String)

  final case class DestroyResponse(
      @description("The location torn down.")
      slug: String,
      @description(
        "True when every task had stopped before anything was deleted. False means the drain was still going when the wait ran out and the teardown went ahead anyway — which is the right call for a teardown, but worth knowing if something was reading what those containers were writing.")
      drained: Boolean,
      @description("True when a live ECS service was deleted, false when there was none left.")
      serviceDeleted: Boolean,
      @description("How many listener rules for this location's path were removed.")
      rulesDeleted: Int,
      @description("True when the target group was deleted, false when it was already gone.")
      targetGroupDeleted: Boolean,
      @description(
        "How many task definition revisions were deregistered. Only this location's family — a prefix match would also catch a neighbour whose slug starts with this one.")
      taskDefinitionsDeregistered: Int,
      @description("The log group, which is kept on purpose: it holds why this was torn down.")
      logGroupKept: String,
      @description(
        "How many objects were deleted from this location's S3 prefix: its copy of the bundle, its stylesheet and icon, and every uploaded video file. Zero when the question about them was declined — or could not be asked.")
      objectsDeleted: Int,
      @description(
        "True when the S3 objects were left in place, because deleting them was declined or no answer came back. The infrastructure is gone either way; a deploy_location puts the portal back on top of whatever survived.")
      assetsKept: Boolean,
      @description(
        "What survives, and what to do next. Nothing in the database was touched, so this location can be deployed again.")
      nextSteps: List[String])

  given Schema[PauseRequest] = DeriveSchema.gen
  given Schema[PauseResponse] = DeriveSchema.gen
  given Schema[ResumeResponse] = DeriveSchema.gen
  given Schema[DestroyRequest] = DeriveSchema.gen
  given Schema[DestroyResponse] = DeriveSchema.gen

  private object PauseSteps:
    val Page = "Swap the location's page for the maintenance page"
    val Edge = "Wait for the edge to drop the location's page"
    val Rule = "Answer 'paused' from the load balancer"
    val Scale = "Scale the service to zero"
    val Drain = "Wait for the running tasks to stop"
    val all = List(Page, Edge, Rule, Scale, Drain)

  /** Pause: scale service to zero and repoint rule to fixed response. Database untouched. */
  private def pauseWith(
      store: InfraStore,
      uiOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("pause_location")
    .description(
      "Take a location's portal out of service without dismantling it. In order: the maintenance page replaces the location's index.html at the edge, the CloudFront invalidation is waited out, and only then is the API stopped — the ALB rule answers a fixed 'paused' JSON and the ECS service is scaled to zero. Writes nothing to the database. resume_location puts every part of it back. Answers at once with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDeploy)
    .handleWithContext: (in: PauseRequest, ctx: McpToolContext) =>
      for
        _        <- requireLocation(in.slug)
        caller   <- callerOf(ctx)
        launched <-
          Runs.launch(in.slug, "pause", PauseSteps.all, caller)(run =>
            pause(store, in.slug, run).map(encode(_)))
        view <- started(launched, caller, uiOrigin)
      yield view
    .withUi(RunUi.AppUri)

  private def pause(
      store: InfraStore,
      slug: String,
      run: Runs.Progress): ZIO[QuillSqlite & AssetStore, String, PauseResponse] =
    for
      assets <- ZIO.service[AssetStore]
      // Update edge before API: ensures guest sees maintenance page before paused response.
      _            <- run.step(PauseSteps.Page)(assets.pausePage(slug))
      invalidation <- run.step(PauseSteps.Edge)(assets.invalidateAndWait(slug, WaitAtMost))
      // API: rule (to fixed response) before scale-down (avoid empty 503).
      rule    <- run.step(PauseSteps.Rule)(store.pauseRule(slug))
      _       <- run.step(PauseSteps.Scale)(store.scaleService(slug, 0))
      drained <-
        run.noting(PauseSteps.Drain)(store.awaitRunning(slug, _ == 0, WaitAtMost))(done =>
          Option.unless(done)("still stopping when the wait ran out"))
    yield PauseResponse(
      slug = slug,
      scaledToZero = true,
      drained = drained,
      ruleArn = rule,
      invalidation = invalidation,
      answersWith = InfraStore.PausedBody,
      nextSteps = List(
        "The edge serves the maintenance page for this location, and the API answers the paused JSON. A guest already inside the SPA reads it on their next call and redirects themselves.",
        "Running tasks stop as the service drains. The rule answers from the load balancer, so the response does not depend on one being alive.",
        "Nothing was written to the database, and nothing the operator authored was touched in S3 — the stylesheet, the icon and the videos are as they were. resume_location puts the page and the API back; deploy_location does too, and also refreshes the bundle."
      )
    )

  private object ResumeSteps:
    val TargetGroup = "Target group"
    val Scale = "Scale the service to one task"
    val Running = "Wait for a task to run"
    val Rule = "Forward to the target group again"
    val Healthy = "Wait for a healthy target"
    val Page = "Put the location's page back"
    val Edge = "Wait for the edge to drop the maintenance page"
    val all = List(TargetGroup, Scale, Running, Rule, Healthy, Page, Edge)

  /** Resume: inverse of pause, using deploy's idempotent steps. */
  private def resumeWith(
      store: InfraStore,
      uiOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("resume_location")
    .description(
      "Put a paused location back in service, in the reverse order of the pause: the service is scaled back to one task and the ALB rule forwards again, then the location's own page replaces the maintenance one at the edge. Restores the page that was live rather than the current bundle — deploy_location is how a location takes a newer one. Idempotent, and safe on a location that was never paused. Answers at once with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDeploy)
    .handleWithContext: (in: PauseRequest, ctx: McpToolContext) =>
      for
        _        <- requireLocation(in.slug)
        caller   <- callerOf(ctx)
        launched <-
          Runs.launch(in.slug, "resume", ResumeSteps.all, caller)(run =>
            resume(store, in.slug, run).map(encode(_)))
        view <- started(launched, caller, uiOrigin)
      yield view
    .withUi(RunUi.AppUri)

  private def resume(
      store: InfraStore,
      slug: String,
      run: Runs.Progress): ZIO[QuillSqlite & AssetStore, String, ResumeResponse] =
    for
      assets <- ZIO.service[AssetStore]
      // API first (inverse of pause): ensure it's answering before changing page.
      targetGroup <- run.step(ResumeSteps.TargetGroup)(store.ensureTargetGroup(slug))
      _           <- run.step(ResumeSteps.Scale)(store.scaleService(slug, 1))
      // Fail if task won't start (keep location paused, not in broken state).
      _ <-
        run.step(ResumeSteps.Running)(
          store
            .awaitRunning(slug, _ >= 1, WaitAtMost)
            .filterOrFail(identity)(
              s"Scaled the service back up, but no task was running after ${WaitAtMost
                  .toMinutes} minutes — so the rule still answers 'paused' and the edge still serves the maintenance page, which is the safe end of a container that will not start. Check ${store
                  .targets
                  .logGroup(slug)} and run resume_location again."))
      // Order: running task, then rule (AWS requires active target before forwarding).
      rule    <- run.step(ResumeSteps.Rule)(store.ensureRule(slug, targetGroup))
      healthy <-
        run.noting(ResumeSteps.Healthy)(store.awaitHealthyTarget(slug, WaitAtMost))(ok =>
          Option.unless(ok)("not yet healthy when the wait ran out"))
      restored <-
        run.noting(ResumeSteps.Page)(assets.resumePage(slug))(found =>
          Option.unless(found)("no saved page was found"))
      invalidation <- run.step(ResumeSteps.Edge)(assets.invalidateAndWait(slug, WaitAtMost))
    yield ResumeResponse(
      slug = slug,
      ruleArn = rule,
      targetGroupArn = targetGroup,
      pageRestored = restored,
      invalidation = invalidation,
      healthy = healthy,
      nextSteps = List(
        "The service has a task again, and the rule forwards to it.",
        if restored then
          "The location's own page is back at the edge, exactly as it was before the pause."
        else
          "No saved page was found, so the edge is serving whatever was already at this location's index.html. That is the expected answer for a location that was not paused; if it *was* paused, run deploy_location to put the bundle's page back."
        ,
        "This did not re-copy the bundle. Use deploy_location if the shared SPA has been published since."
      )
    )

  private object DestroySteps:
    val Scale = "Scale the service to zero"
    val Drain = "Wait for the running tasks to stop"
    val Teardown = "Remove the service, its rules, target group and task definitions"
    val Delete = "Delete the location's S3 objects"
    val Keep = "Keep the location's S3 objects"

  /** Remove AWS resources but keep database and log group. Requires slug confirmation. */
  private def destroyWith(
      store: InfraStore,
      uiOrigin: String): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("destroy_location")
    .description(
      "Remove everything a location has in AWS: the ECS service, its listener rule, its target group, its task definition revisions, and its entire S3 prefix — the bundle copy, the stylesheet, the icon and the uploaded video files. Requires the slug typed again as confirmation. Keeps the log group and touches nothing in the database, so the location, its translations and its survey definitions survive and deploy_location can stand the portal up again — but the video files are gone and their rows will point at nothing. For temporary downtime use pause_location, which deletes nothing. Asks about the S3 objects in the call, then answers with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDestroy)
    .annotations(destructive = OptBool.True, idempotent = OptBool.True)
    .handleWithContext: (in: DestroyRequest, ctx: McpToolContext) =>
      for
        _ <- requireLocation(in.slug)
        _ <-
          ZIO.unless(in.confirm == in.slug):
            ZIO.fail(
              s"Refused: `confirm` has to be '${in.slug}' exactly, and it was '${in
                  .confirm}'. This removes the service, the rule, the target group and every task definition revision for the location.")
        caller <- callerOf(ctx)
        // Elicit assets deletion before teardown (handler re-runs on retry).
        assets <- ZIO.service[AssetStore]
        keys   <- assets.listPrefix(s"${in.slug}/")
        videos = keys.count(_.startsWith(s"${in.slug}/videos/"))
        // Declined is the default when there is no answer: `McpToolContext.elicit` returns
        // `decline` for an unanswered request, and fails for a client that cannot ask at all, which
        // `option` turns into no answer. So a host that cannot ask keeps the objects, and the one
        // irreversible step here is the one that needs something to actively say yes.
        answer <-
          ctx
            .elicit(
              s"Also delete this location's ${keys
                  .size} objects from S3? That includes $videos uploaded video file${
                  if videos == 1 then
                    ""
                  else
                    "s"
                }, its stylesheet and its brand icon. The database is not touched either way, so the video rows will survive pointing at files that no longer exist. Declining removes the infrastructure and leaves every object in place.",
              Json.Obj(
                "type"       -> Json.Str("object"),
                "properties" ->
                  Json.Obj(
                    "deleteAssets" ->
                      Json.Obj(
                        "type"        -> Json.Str("boolean"),
                        "description" ->
                          Json.Str(
                            "True to delete the location's S3 objects, including its uploaded videos."))),
                "required" -> Json.Arr(Json.Str("deleteAssets"))
              )
            )
            .option
        // Accepting the question with deleteAssets=true means delete.
        wanted = answer.exists(answered =>
          answered.action == "accept" &&
            !answered.content.flatMap(_.get("deleteAssets")).flatMap(_.asBoolean).contains(false))
        steps = List(
          DestroySteps.Scale,
          DestroySteps.Drain,
          DestroySteps.Teardown,
          if wanted then
            DestroySteps.Delete
          else
            DestroySteps.Keep)
        launched <-
          Runs.launch(in.slug, "destroy", steps, caller)(run =>
            destroy(store, in.slug, wanted, run).map(encode(_)))
        view <- started(launched, caller, uiOrigin)
      yield view
    .withUi(RunUi.AppUri)

  private def destroy(
      store: InfraStore,
      slug: String,
      wanted: Boolean,
      run: Runs.Progress): ZIO[QuillSqlite & AssetStore, String, DestroyResponse] =
    for
      assets <- ZIO.service[AssetStore]
      // Drain tasks before deleting resources. No live service is not a failure here.
      _       <- run.step(DestroySteps.Scale)(store.scaleService(slug, 0).either.unit)
      drained <-
        run.noting(DestroySteps.Drain)(store.awaitRunning(slug, _ == 0, WaitAtMost))(done =>
          Option.unless(done)("still stopping when the wait ran out; torn down anyway"))
      report  <- run.step(DestroySteps.Teardown)(store.destroy(slug))
      objects <-
        if wanted then
          run.noting(DestroySteps.Delete)(
            assets.deletePrefix(s"$slug/") <* assets.deletePrefix(AssetStore.savedPageKey(slug)))(
            count => Some(s"$count objects"))
        else
          run.step(DestroySteps.Keep)(ZIO.succeed(0))
    yield DestroyResponse(
      slug = slug,
      drained = drained,
      serviceDeleted = report.serviceDeleted,
      rulesDeleted = report.rulesDeleted,
      targetGroupDeleted = report.targetGroupDeleted,
      taskDefinitionsDeregistered = report.taskDefinitionsDeregistered,
      logGroupKept = report.logGroup,
      objectsDeleted = objects,
      assetsKept = !wanted,
      nextSteps = List(
        s"${report
            .logGroup} was kept, because it holds why this was torn down. Delete it by hand if it is not wanted.",
        "The database is untouched: the location row, its translations, surveys, videos and tokens are all still there, and list_locations still reports it.",
        s"$objects objects were deleted from S3 — the bundle copy, the stylesheet, the icon and the uploaded video files. **The video rows survive and now point at files that are gone**, so a redeploy restores the portal but every video needs uploading again: list_pending_uploads will not find them, because they are still marked active. Retire them with set_video_active or re-create them.",
        "deploy_location rebuilds the infrastructure and copies the bundle back, so the portal itself returns with no further steps.",
        "Anything already false here was already gone — a teardown that follows a failed one reports what it found rather than failing."
      )
    )

  final case class StatusRequest(
      @description("The runId a deploy, pause, resume or destroy answered with.")
      runId: Option[String],
      @description("A location's slug, for its latest run when the runId is not at hand.")
      slug: Option[String])

  final case class RunStep(
      @description("What the step does.")
      name: String,
      @description("pending, running, done or failed.")
      state: String,
      @description("What it found or why it failed, when there is something to say.")
      detail: Option[String])

  final case class RunWatch(
      @description("Authorises reading this run's progress, and nothing else.")
      ticket: String,
      @description("Where the page posts the ticket to receive the run as Server-Sent Events.")
      streamUrl: String)

  final case class RunView(
      @description("Identifies this run; get_deploy_status takes it.")
      runId: String,
      slug: String,
      @description("deploy, pause, resume or destroy.")
      operation: String,
      @description(
        "running, succeeded or failed — or interrupted, when the server doing the work stopped before it finished.")
      status: String,
      @description("Every step in order. Their names are what to show an operator.")
      steps: List[RunStep],
      startedAt: String,
      @description("When the work last reported in.")
      updatedAt: String,
      finishedAt: Option[String],
      @description("Why it failed, once it did.")
      error: Option[String],
      @description("A deploy's result, once it succeeded.")
      deployed: Option[DeployResponse],
      @description("A pause's result, once it succeeded.")
      paused: Option[PauseResponse],
      @description("A resume's result, once it succeeded.")
      resumed: Option[ResumeResponse],
      @description("A destroy's result, once it succeeded.")
      destroyed: Option[DestroyResponse],
      @description("What to do now.")
      nextSteps: List[String],
      @description(
        "For the page that shows this run, on the answer of the call that started it. Not for the model to use.")
      watch: Option[RunWatch])

  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[RunStep] = DeriveSchema.gen
  given Schema[RunWatch] = DeriveSchema.gen
  given Schema[RunView] = DeriveSchema.gen

  private def encode[A](value: A)(using schema: Schema[A]): String =
    SchemaJsonCodec.jsonEncoder(schema).encodeJson(value, None).toString

  private def decode[A](row: Runs.Row, operation: String)(using schema: Schema[A]): Option[A] = row
    .result
    .filter(_ => row.operation == operation)
    .flatMap(SchemaJsonCodec.jsonDecoder(schema).decodeJson(_).toOption)

  private def toolFor(operation: String): String = s"${operation}_location"

  /** The answer of a call that launched a run, or found one going: the run, and what the page in
    * [[RunUi]] needs to follow it. `Left` from [[Runs.launch]] is a run this call did not start.
    */
  private def started(
      launched: Either[Runs.Row, Runs.Row],
      caller: String,
      uiOrigin: String): ZIO[QuillSqlite, String, RunView] =
    for
      view <- launched.fold(
        going => viewOf(going, alreadyGoing = true),
        started => viewOf(started, alreadyGoing = false))
      token <- Tokens
        .byIdentifier(caller)
        .someOrFail("The token that made this call no longer exists.")
    yield view.copy(watch = Some(
      RunWatch(
        ticket = RunTicket.mint(token.tokenSha256, caller, view.runId),
        streamUrl = s"$uiOrigin${RunRoutes.EventsPath}")))

  private[mcp] def viewOf(row: Runs.Row, alreadyGoing: Boolean): UIO[RunView] = Clock
    .instant
    .map: now =>
      val status = Runs.statusOf(row, now)
      val steps = Runs.steps(row)
      val stoppedAt = steps
        .find(step => step.state == Runs.StepFailed || step.state == Runs.StepRunning)
        .map(_.name)
      val at = stoppedAt.fold("")(name => s" at '$name'")
      val next =
        status match
          case Runs.Running if alreadyGoing =>
            List(
              s"A ${row.operation} of '${row
                  .slug}' was already running, so this call started nothing; this is that run. Follow it with get_deploy_status.")
          case Runs.Running =>
            List(
              "Running in the background, and it does not need this conversation to stay open. Call get_deploy_status with this runId about every 30 seconds until status is no longer running.")
          case Runs.Succeeded =>
            List(s"Done. What is left is in the ${row.operation} result's own nextSteps.")
          case Runs.Interrupted =>
            List(
              s"The server doing this stopped before it finished$at. Every step is idempotent, so calling ${toolFor(
                  row.operation)} again finishes it.")
          case _ =>
            List(
              s"Failed$at. Every step is idempotent, so once the cause is dealt with, calling ${toolFor(
                  row.operation)} again redoes what was done in moments and carries on from there.")
      RunView(
        runId = row.id,
        slug = row.slug,
        operation = row.operation,
        status = status,
        steps = steps.map(step => RunStep(step.name, step.state, step.detail)),
        startedAt = row.startedAt,
        updatedAt = row.heartbeatAt,
        finishedAt = row.finishedAt,
        error = row.error,
        deployed = decode[DeployResponse](row, "deploy"),
        paused = decode[PauseResponse](row, "pause"),
        resumed = decode[ResumeResponse](row, "resume"),
        destroyed = decode[DestroyResponse](row, "destroy"),
        nextSteps = next,
        watch = None
      )

  private val status: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_deploy_status")
    .description(
      "Where a deploy, pause, resume or destroy of a location's portal stands: every step and its state, and the operation's result once it has one. Those four answer at once and leave the work running in the background, so call this about every 30 seconds until status is no longer running. Pass the runId they answered with, or a slug for that location's latest run. Call read_skill('deploy-location') first.")
    .readOnly
    .handle: (in: StatusRequest) =>
      for
        row <-
          (in.runId, in.slug) match
            case (Some(id), _) =>
              Runs.get(id).someOrFail(s"No run with id '$id'.")
            case (None, Some(slug)) =>
              Runs
                .latest(slug)
                .someOrFail(s"No deploy, pause, resume or destroy has run for '$slug'.")
            case (None, None) =>
              ZIO.fail("Pass a runId, or a slug for its latest run.")
        view <- viewOf(row, alreadyGoing = false)
      yield view

  /** All five tools, or none. Registered only when deploy configuration exists.
    *
    * @param uiOrigin
    *   where this server is reached, which the run page streams from.
    */
  def all(
      store: Option[InfraStore],
      uiOrigin: String): List[McpToolHandlerR[QuillSqlite & AssetStore]] = store
    .toList
    .flatMap(value =>
      List(
        deployWith(value, uiOrigin),
        pauseWith(value, uiOrigin),
        resumeWith(value, uiOrigin),
        destroyWith(value, uiOrigin),
        status))
end DeployTools
