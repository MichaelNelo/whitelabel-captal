package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.Writes
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.aws.{AssetStore, BundleCopy, InfraStore}
import whitelabel.captal.mcp.protocol.McpSchema
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.json.ast.Json
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given

/** Four tools: create, pause, resume, destroy a location's AWS infrastructure. */
object DeployTools:

  /** Health check timeout: 3 minutes (ECS health-check grace period is 180 seconds). */
  private val HealthTimeout: Duration = 3.minutes

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
      @description("True when the service was created by this call, false when it was updated.")
      created: Boolean,
      @description(
        "True when a task was running before this returned. False means ECS accepted everything and no container came up inside the timeout — the resources are correct and the portal is not serving; the log group is where to look.")
      running: Boolean,
      @description(
        "True when the load balancer reported a healthy target, which is the first thing here that means a guest would be answered by the portal rather than by a 503.")
      healthy: Boolean,
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
      @description(
        "What is left. Nothing here waits for the container to pass its health checks, so a successful call means the resources exist, not that a guest can load the portal yet.")
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

  /** Tool declaration without store (for testing). */
  private[mcp] val declaration: McpTool = McpTool("deploy_location")
    .description(
      "Stand up a location's portal in AWS: log group, target group, ALB listener rule, task definition and ECS service. Idempotent — a location already deployed has its service updated to a fresh task definition rather than a second one created. Does not wait for the container to pass health checks, so success means the resources exist, not that the portal answers yet.")
    .requireScopes(Auth.InfraDeploy)
    // TaskExecution.Required: waits up to 3 minutes for health checks (not synchronous).
    .taskExecution(TaskExecution.Required, ttl = Some(15.minutes))

  /** Note for synchronous callers (silent when using tasks extension). */
  private[mcp] def taskNote(capabilities: Map[McpExtensionId, Json]): List[String] =
    if capabilities.contains(McpTasks.Id) then
      Nil
    else
      List(
        "This answered in the call itself, because your client did not declare the tasks extension. If a deploy ever ends in a transport timeout rather than an error, that is not evidence it failed: call deploy_location again — every step is idempotent — and compare what it reports.")

  /** Takes store by value (not from environment) to keep server type as QuillSqlite & AssetStore.
    */
  private def deployWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = declaration
    .handleWithContext: (in: DeployRequest, ctx: McpToolContext) =>
      for
        location <- requireLocation(in.slug)
        assets   <- ZIO.service[AssetStore]
        // Copy bundle first (before AWS resources exist).
        bundle       <- assets.copyBundle(in.slug)
        invalidation <- assets.invalidatePrefix(in.slug)
        // Log group, target group, rule, task def, service (sequential order).
        logGroup    <- store.ensureLogGroup(in.slug)
        targetGroup <- store.ensureTargetGroup(in.slug)
        rule        <- store.ensureRule(in.slug, targetGroup)
        taskDef     <- store.registerTaskDefinition(in.slug)
        result      <- store.upsertService(in.slug, taskDef, targetGroup)
        (serviceArn, created) = result
        // Wait for task to be running (not just resources created).
        running <- store.awaitRunning(in.slug, _ >= 1, HealthTimeout)
        healthy <- store.awaitHealthyTarget(in.slug, HealthTimeout)
      yield DeployResponse(
        slug = in.slug,
        logGroup = logGroup,
        targetGroupArn = targetGroup,
        ruleArn = rule,
        taskDefinitionArn = taskDef,
        serviceArn = serviceArn,
        created = created,
        running = running,
        healthy = healthy,
        bundleObjects = bundle.objects,
        iconPreserved = bundle.iconPreserved,
        invalidation = invalidation,
        pathPattern = store.targets.pathPattern(in.slug),
        nextSteps =
          List(
            s"The service is ${
                if created then
                  "starting for the first time"
                else
                  "rolling to the new revision"
              }; health checks have a 180s grace period. Watch $logGroup if it does not come up.",
            "Call get_location_readiness to see whether the content is complete — this call created infrastructure and wrote nothing to the database.",
            if location.isActive == 1 then
              "The location is active, so it will serve as soon as the container is healthy."
            else
              "The location is retired in the database, so the portal will serve nothing even once the container is healthy. Bring it back with update_location."
          ) ++ bundleNote(bundle) ++ taskNote(ctx.extensionCapabilities)
      )

  /** Note when bundle objects are zero (portal page 404s). */
  private def bundleNote(bundle: BundleCopy): List[String] =
    if bundle.objects > 0 then
      Nil
    else
      List(
        "No bundle objects were copied, because the shared bundle prefix is empty. The portal page itself will 404 until the SPA is published with the client.publishS3 Mill task; re-run deploy_location afterwards to copy it under this slug.")

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
        "True when every task had actually stopped before this returned. False means the drain was still going at the timeout, which does not affect the pause — the rule stopped forwarding before any of this — but does mean a container may still be running against whatever you were about to change.")
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
        "True when the load balancer reported a healthy target before this returned. False means the task is running but the ALB has not finished health-checking it yet — the portal answers 503 until it does, and nothing is wrong.")
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
        "True when every task had stopped before anything was deleted. False means the drain was still going at the timeout and the teardown went ahead anyway — which is the right call for a teardown, but worth knowing if something was reading what those containers were writing.")
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
        "What survives, and what to do next. Nothing in the database or in S3 was touched, so this location can be deployed again.")
      nextSteps: List[String])

  given Schema[PauseRequest] = DeriveSchema.gen
  given Schema[PauseResponse] = DeriveSchema.gen
  given Schema[ResumeResponse] = DeriveSchema.gen
  given Schema[DestroyRequest] = DeriveSchema.gen
  given Schema[DestroyResponse] = DeriveSchema.gen

  /** Pause: scale service to zero and repoint rule to fixed response. Database untouched. */
  private def pauseWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "pause_location")
    .description(
      "Take a location's portal out of service without dismantling it. In order: the maintenance page replaces the location's index.html at the edge, the CloudFront invalidation is waited out, and only then is the API stopped — the ALB rule answers a fixed 'paused' JSON and the ECS service is scaled to zero. Writes nothing to the database. resume_location puts every part of it back.")
    .requireScopes(Auth.InfraDeploy)
    // TaskExecution.Required: waits for CloudFront invalidation (minutes).
    .taskExecution(TaskExecution.Required, ttl = Some(15.minutes))
    .handle: (in: PauseRequest) =>
      for
        _      <- requireLocation(in.slug)
        assets <- ZIO.service[AssetStore]
        // Update edge before API: ensures guest sees maintenance page before paused response.
        _            <- assets.pausePage(in.slug)
        invalidation <- assets.invalidateAndWait(in.slug)
        // API: rule (to fixed response) before scale-down (avoid empty 503).
        rule <- store.pauseRule(in.slug)
        _    <- store.scaleService(in.slug, 0)
        // Wait for tasks to actually stop (not just requested).
        drained <- store.awaitRunning(in.slug, _ == 0, HealthTimeout)
      yield PauseResponse(
        slug = in.slug,
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

  /** Resume: inverse of pause, using deploy's idempotent steps. */
  private def resumeWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "resume_location")
    .description(
      "Put a paused location back in service, in the reverse order of the pause: the ALB rule forwards again and the service is scaled back to one task, then the location's own page replaces the maintenance one at the edge. Restores the page that was live rather than the current bundle — deploy_location is how a location takes a newer one. Idempotent, and safe on a location that was never paused.")
    .requireScopes(Auth.InfraDeploy)
    // TaskExecution.Required: waits for CloudFront invalidation (mirror of pause).
    .taskExecution(TaskExecution.Required, ttl = Some(15.minutes))
    .handle: (in: PauseRequest) =>
      for
        _      <- requireLocation(in.slug)
        assets <- ZIO.service[AssetStore]
        // API first (inverse of pause): ensure it's answering before changing page.
        targetGroup <- store.ensureTargetGroup(in.slug)
        _           <- store.scaleService(in.slug, 1)
        // Wait for healthy target (avoid empty 503 on empty target group).
        running <- store.awaitRunning(in.slug, _ >= 1, HealthTimeout)
        // Fail if task won't start (keep location paused, not in broken state).
        _ <-
          ZIO.unless(running):
            ZIO.fail(
              s"Scaled the service back up, but no task was running after ${HealthTimeout
                  .toSeconds}s — so the rule still answers 'paused' and the edge still serves the maintenance page, which is the safe end of a container that will not start. Check ${store
                  .targets
                  .logGroup(in.slug)} and run resume_location again.")
        // Order: running task, then rule (AWS requires active target before forwarding).
        rule         <- store.ensureRule(in.slug, targetGroup)
        healthy      <- store.awaitHealthyTarget(in.slug, HealthTimeout)
        restored     <- assets.resumePage(in.slug)
        invalidation <- assets.invalidateAndWait(in.slug)
      yield ResumeResponse(
        slug = in.slug,
        ruleArn = rule,
        targetGroupArn = targetGroup,
        pageRestored = restored,
        invalidation = invalidation,
        healthy = healthy,
        nextSteps = List(
          "The service is starting a task again; health checks have a 180s grace period, and the rule forwards as soon as one target is healthy.",
          if restored then
            "The location's own page is back at the edge, exactly as it was before the pause."
          else
            "No saved page was found, so the edge is serving whatever was already at this location's index.html. That is the expected answer for a location that was not paused; if it *was* paused, run deploy_location to put the bundle's page back."
          ,
          "This did not re-copy the bundle. Use deploy_location if the shared SPA has been published since."
        )
      )

  /** Remove AWS resources but keep database and log group. Requires slug confirmation. */
  private def destroyWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "destroy_location")
    .description(
      "Remove everything a location has in AWS: the ECS service, its listener rule, its target group, its task definition revisions, and its entire S3 prefix — the bundle copy, the stylesheet, the icon and the uploaded video files. Requires the slug typed again as confirmation. Keeps the log group and touches nothing in the database, so the location, its translations and its survey definitions survive and deploy_location can stand the portal up again — but the video files are gone and their rows will point at nothing. For temporary downtime use pause_location, which deletes nothing.")
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
        // Elicit assets deletion before teardown (handler re-runs on retry).
        assets <- ZIO.service[AssetStore]
        keys   <- assets.listPrefix(s"${in.slug}/")
        videos = keys.count(_.startsWith(s"${in.slug}/videos/"))
        // Declined is the default when there is no answer: `McpToolContext.elicit` returns
        // `decline` for an unanswered request, which is also what a client with no elicitation
        // support produces. So a host that cannot ask keeps the objects, and the one irreversible
        // step here is the one that needs something to actively say yes.
        answer <- ctx
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
          .mapError(error => s"Could not ask about the S3 objects: ${error.message}")
        // Accepting the question with deleteAssets=true means delete.
        wanted =
          answer.action == "accept" &&
            !answer.content.flatMap(_.get("deleteAssets")).flatMap(_.asBoolean).contains(false)
        // Run effects only on answered pass (after elicit).
        // Drain tasks before deleting resources.
        _       <- store.scaleService(in.slug, 0).either
        drained <- store.awaitRunning(in.slug, _ == 0, HealthTimeout)
        report  <- store.destroy(in.slug)
        objects <-
          if wanted then
            assets.deletePrefix(s"${in.slug}/") <*
              assets.deletePrefix(AssetStore.savedPageKey(in.slug))
          else
            ZIO.succeed(0)
      yield DestroyResponse(
        slug = in.slug,
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

  /** All four tools, or none. Registered only when deploy configuration exists. */
  def all(store: Option[InfraStore]): List[McpToolHandlerR[QuillSqlite & AssetStore]] = store
    .toList
    .flatMap(value =>
      List(deployWith(value), pauseWith(value), resumeWith(value), destroyWith(value)))
end DeployTools
