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

/** A location's portal in AWS: standing it up, taking it out of service, and removing it.
  *
  * Four tools over one [[InfraStore]], and the line between them is what each one leaves behind.
  * `deploy_location` creates; `pause_location` scales to nothing and makes the load balancer answer
  * instead of forward; `resume_location` is the deploy's own steps run again; `destroy_location`
  * removes what the deploy made. None of them writes to the database or to S3, so a location's
  * content outlives its infrastructure in every direction.
  *
  * This is the half of a location that `create_location` does not do, and until now nothing did.
  * `captal locations push` created the service, the target group and the listener rule; it was
  * deleted with the rest of the CLI, which left `create_location` writing a row that nothing in AWS
  * serves. A guest opening `https://…/<slug>/` got the SPA from CloudFront and a 403 from the ALB
  * on every API call, because no rule matched the path.
  *
  * The shared bundle first, copied under this slug in S3 — the step the CLI did first and nothing
  * did after it was deleted, which left a deployed location answering its API and serving no page.
  * Before AWS rather than after, so a missing bucket stops the call while there is still nothing to
  * clean up. Then five resources, in an order that is not arbitrary either:
  *
  *   1. the log group, so the task's first lines have somewhere to land
  *   2. the target group, which the rule forwards to
  *   3. the **rule**, before the service — `createService` with a `loadBalancers` block fails with
  *      `400 no associated load balancer` when the target group is not reachable through a
  *      listener. The CLI had this inverted and it is recorded in AGENTS.md as a historical bug
  *   4. the task definition, carrying `LOCATION_SLUG` and `DB_URL`
  *   5. the service, created or updated
  *
  * It does not wait for the service to become stable, and that is deliberate. The CLI polled, with
  * no timeout, which is what made it unusable from a tool call; and waiting would say nothing a
  * caller cannot ask for. `deploy_location` reports what it created, and whether the portal is
  * actually answering is a different question with a different answer — `get_location_readiness`
  * for the content, the service's own events for the container. If a blocking "wait until healthy"
  * is wanted later, that is what the Tasks extension is for.
  */
object DeployTools:

  /** How long any of these waits for the thing it asked for.
    *
    * Matched to the service's own 180-second health-check grace period: less would give up on
    * containers ECS is still willing to wait for, and much more would hold a task open on one that
    * is not coming up. The tasks' own TTL is 15 minutes, well past this.
    */
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

  /** Refuses a slug with no row rather than creating infrastructure for one.
    *
    * The resources are all named after the slug, so deploying one that does not exist would leave a
    * service, a target group and a rule that no content will ever be written for, and nothing to
    * find them by — `list_locations` reads the database.
    */
  private def requireLocation(slug: String): ZIO[QuillSqlite, String, LocationRow] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(locationBySlugQuery(lift(slug)))
        .orDie
        .map(_.headOption)
        .someOrFail(
          s"No location with slug '$slug'. Call create_location first — every resource this creates is named after the slug.")

  /** Everything this tool declares about itself, with no store behind it yet.
    *
    * Split out so the scope and the task policy can be read without one. An [[InfraStore]] holds
    * three AWS clients, and building them resolves a region and credentials from the environment —
    * so a test that only wants to know what the tool *claims* would otherwise either need an AWS
    * account or pass under a misconfiguration that silently changed the claim.
    */
  private[mcp] val declaration: McpTool = McpTool("deploy_location")
    .description(
      "Stand up a location's portal in AWS: log group, target group, ALB listener rule, task definition and ECS service. Idempotent — a location already deployed has its service updated to a fresh task definition rather than a second one created. Does not wait for the container to pass health checks, so success means the resources exist, not that the portal answers yet.")
    .requireScopes(Auth.InfraDeploy)
    // `Required`, which this was not. It began as `WhenSupported` on the reasoning that five AWS
    // calls that wait for nothing are safe to answer in line — true at the time, and no longer: it
    // now waits for a task to come up and for the load balancer to call it healthy, which is the
    // difference between reporting that the resources exist and reporting that a guest can load the
    // portal. A wait of up to three minutes has no sensible synchronous form, and a client cut off
    // half way through it would be left not knowing which.
    //
    // A client that cannot poll gets `-32021` and has created nothing, which is recoverable.
    .taskExecution(TaskExecution.Required, ttl = Some(15.minutes))

  /** What to say about how this call was answered, which only matters when it was not a task.
    *
    * Read off the request rather than documented, because the answer differs per client and no
    * document can be right for all of them. The map comes from `McpToolContext`, populated from the
    * same `_meta.io.modelcontextprotocol/clientCapabilities.extensions` the server itself reads to
    * decide — so this is the server's own condition rather than a second guess at it. Taken as the
    * map and not the whole context because that is all it needs, and because a context is not
    * something a test can construct.
    *
    * Silent in the task case on purpose: a client that got a task handle already knows, and a line
    * on every successful deploy saying "this went well" is the kind of noise that gets `nextSteps`
    * skipped. What is worth saying is the one thing a synchronous caller cannot work out alone — a
    * timeout here is not evidence the deploy did not happen.
    */
  private[mcp] def taskNote(capabilities: Map[McpExtensionId, Json]): List[String] =
    if capabilities.contains(McpTasks.Id) then
      Nil
    else
      List(
        "This answered in the call itself, because your client did not declare the tasks extension. If a deploy ever ends in a transport timeout rather than an error, that is not evidence it failed: call deploy_location again — every step is idempotent — and compare what it reports.")

  /** Takes the store by value rather than from the environment, which keeps the server's type as
    * `QuillSqlite & AssetStore`.
    *
    * Asking for `InfraStore` would widen it for every tool, and then a deployment with no cluster
    * configured could not build the server at all — the type would demand a service it has no layer
    * for. `UnifiTools.all(uiOrigin)` is parameterised for the same reason.
    */
  private def deployWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = declaration
    .handleWithContext: (in: DeployRequest, ctx: McpToolContext) =>
      for
        location <- requireLocation(in.slug)
        assets   <- ZIO.service[AssetStore]
        // The bundle first, before anything in AWS exists. Two reasons: a missing bucket should
        // stop the call before it has created a service and a listener rule to clean up, and the
        // SPA being in place when the container first answers is one less transient 404.
        bundle       <- assets.copyBundle(in.slug)
        invalidation <- assets.invalidatePrefix(in.slug)
        // The order the comments above record. Written as a sequence rather than a parallel fan-out
        // because each step feeds the next, and because a half-built deploy is easier to read when
        // it stopped at a named step.
        logGroup    <- store.ensureLogGroup(in.slug)
        targetGroup <- store.ensureTargetGroup(in.slug)
        rule        <- store.ensureRule(in.slug, targetGroup)
        taskDef     <- store.registerTaskDefinition(in.slug)
        result      <- store.upsertService(in.slug, taskDef, targetGroup)
        (serviceArn, created) = result
        // Waited for, not assumed. This used to return the moment the five resources existed, which
        // is a different claim from "a guest can load the portal" — and the only one a caller
        // actually wants. The rule already forwards by now, so the target group is routed to and the
        // load balancer will health-check it; before the rule exists it reports `unused` and is
        // never checked at all.
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

  /** Said only when the copy found nothing, which is the one outcome a caller cannot read off the
    * other fields: every AWS resource exists, the call succeeded, and the portal's own page 404s.
    */
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

  /** Out of service without being dismantled.
    *
    * Two changes, and both are needed. Scaling to zero stops the tasks, which is what makes the
    * portal actually unreachable rather than merely discouraged. Repointing the rule is what makes
    * the result an answer: a forward rule to a target group with no healthy members returns an
    * empty 503, which the portal cannot tell from an outage, so it would retry and show an error.
    * The fixed response says `paused`, and the load balancer serves it with nothing running behind.
    *
    * Nothing is written to the database, which is the whole point — the location, its translations,
    * its videos and its branding are all untouched, and `deploy_location` brings it back because it
    * already sets the rule to forward and the service to one task.
    */
  private def pauseWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "pause_location")
    .description(
      "Take a location's portal out of service without dismantling it. In order: the maintenance page replaces the location's index.html at the edge, the CloudFront invalidation is waited out, and only then is the API stopped — the ALB rule answers a fixed 'paused' JSON and the ECS service is scaled to zero. Writes nothing to the database. resume_location puts every part of it back.")
    .requireScopes(Auth.InfraDeploy)
    // `Required`, unlike the deploy, for a reason this tool has and that one does not: it waits out
    // a CloudFront invalidation, which is seconds to minutes. There is no sensible synchronous
    // version of that wait — a client cut off by a transport timeout half way through leaves the
    // edge serving maintenance and the API still live, which is the one state this whole sequence
    // exists to avoid. A client that cannot poll gets `-32021` and has changed nothing.
    .taskExecution(TaskExecution.Required, ttl = Some(15.minutes))
    .handle: (in: PauseRequest) =>
      for
        _      <- requireLocation(in.slug)
        assets <- ZIO.service[AssetStore]
        // The edge first, and this ordering is the point. Swapping the page and then waiting for the
        // invalidation means that by the time anything can receive the paused response, the page it
        // will be sent to is already the one being served. The other way round, a guest is told
        // "paused" and redirected to a URL the edge still answers with the cached portal.
        _            <- assets.pausePage(in.slug)
        invalidation <- assets.invalidateAndWait(in.slug)
        // Now the API, rule before scale-down: the reverse leaves a window where the target group
        // is empty and a forward rule answers an empty 503 that nothing can interpret.
        rule <- store.pauseRule(in.slug)
        _    <- store.scaleService(in.slug, 0)
        // Waited out, so "paused" means the tasks are gone rather than asked to go. Nothing reaches
        // them either way — the rule stopped forwarding first — so this is not about correctness of
        // the pause; it is so that a caller who pauses in order to then change something underneath
        // knows the containers are not still running against it.
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

  /** The exact inverse, and deliberately built out of the deploy's own steps.
    *
    * `ensureTargetGroup` and `ensureRule` are idempotent and set the rule back to forwarding, so
    * resuming is the same code that deployed it rather than a second implementation that could
    * drift from it.
    */
  private def resumeWith(store: InfraStore): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "resume_location")
    .description(
      "Put a paused location back in service, in the reverse order of the pause: the ALB rule forwards again and the service is scaled back to one task, then the location's own page replaces the maintenance one at the edge. Restores the page that was live rather than the current bundle — deploy_location is how a location takes a newer one. Idempotent, and safe on a location that was never paused.")
    .requireScopes(Auth.InfraDeploy)
    // Also `Required`, and also because of the invalidation. The order is the mirror of the pause,
    // so the exposure if it is cut off half way is the mirror too: the edge still serving
    // maintenance over an API that is already answering.
    .taskExecution(TaskExecution.Required, ttl = Some(15.minutes))
    .handle: (in: PauseRequest) =>
      for
        _      <- requireLocation(in.slug)
        assets <- ZIO.service[AssetStore]
        // The API first, which is the inverse of the pause for the same reason: nothing should be
        // able to reach the real page while the thing it calls is still refusing.
        targetGroup <- store.ensureTargetGroup(in.slug)
        _           <- store.scaleService(in.slug, 1)
        // And a task has to be *healthy* before the rule stops answering and starts forwarding. A
        // forward rule to a target group with nothing healthy in it answers an empty 503 — the
        // uninterpretable failure the paused body exists to replace — so flipping it over an empty
        // target group trades a deliberate "paused" for an accidental "broken" for as long as the
        // container takes to boot.
        //
        // This is the wait `deploy_location` does not need, which is worth stating because the two
        // look alike: creating has nothing to break, and a redeploy rolls under the service's
        // `minimumHealthyPercent(100)`, which keeps the old task until the new one is healthy.
        running <- store.awaitRunning(in.slug, _ >= 1, HealthTimeout)
        // Refused rather than forced, leaving the location exactly as it was — paused, with the
        // maintenance page still at the edge. A resume onto a container that will not come up should
        // end with the guest still seeing maintenance, not a 503.
        _ <-
          ZIO.unless(running):
            ZIO.fail(
              s"Scaled the service back up, but no task was running after ${HealthTimeout
                  .toSeconds}s — so the rule still answers 'paused' and the edge still serves the maintenance page, which is the safe end of a container that will not start. Check ${store
                  .targets
                  .logGroup(in.slug)} and run resume_location again.")
        // Only now can the rule forward, and only after it does can the target be health-checked at
        // all — AWS reports a target group nothing routes to as `unused`. So this order is forced:
        // running task, then rule, then health. There is a short window after the flip in which the
        // load balancer has not yet health-checked the new target and answers 503; that is what
        // `healthCheckGracePeriodSeconds` exists for and it cannot be designed away with one target
        // group.
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

  /** Everything the deploy created, removed — and nothing else.
    *
    * Gated on the slug being typed out again rather than on an elicitation round trip. MRTR was the
    * plan and it is the wrong gate for this one tool: `elicitation/create` is a client capability,
    * and a host that does not implement it would leave the only destructive operation here either
    * unusable or, worse, ungated. An argument the caller has to repeat works everywhere and is
    * equally hard to do by accident.
    *
    * What it does not touch is as much the contract as what it does: no row, no translation, no
    * video, no stylesheet, no icon, and not the log group. A destroyed location is one
    * `deploy_location` away from serving again.
    */
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
        // The question first, before anything is touched, and that ordering is forced rather than
        // stylistic. An elicitation under MRTR is not a pause: the server answers `input_required`,
        // the client retries the whole call with the answer, and **the handler runs again from the
        // top**. So everything above an `elicit` happens twice. With the teardown above it, the
        // first pass deleted the service, the rule and the target group and then suspended, and the
        // second pass reported zeros for all of it because it found nothing left — which is exactly
        // what this spec caught.
        //
        // Above the elicit there are now only reads, so running them twice costs two listings.
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
        // Accepting the question is the yes. The boolean is read only to honour a client that
        // renders the schema as a form and sends it back false — accepting while answering "no"
        // means no.
        wanted =
          answer.action == "accept" &&
            !answer.content.flatMap(_.get("deleteAssets")).flatMap(_.asBoolean).contains(false)
        // Only now the effects, which run once: this is the pass that has the answer.
        //
        // Drained before anything is deleted. `deleteService(force = true)` stops the tasks, but
        // asynchronously, so without this the target group and the rule go while containers are
        // still running against them — and a teardown that reports success with tasks alive is the
        // kind that gets run twice.
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

  /** Registered only when the environment has deploy configuration, so a content-only deployment
    * does not advertise tools whose first call would fail on a missing cluster name. `tools/list`
    * not mentioning them is better than tools that exist and cannot work.
    */
  /** All four, or none.
    *
    * One list rather than two because three of them reach S3 — the deploy copies the bundle, and
    * pause and resume swap the page the edge serves — so they all want `AssetStore`.
    * `destroy_location` does not, and does not need to be separated for it: `McpToolHandlerR` is
    * contravariant in its environment, so a handler that asks for less fits a server that offers
    * more.
    */
  def all(store: Option[InfraStore]): List[McpToolHandlerR[QuillSqlite & AssetStore]] = store
    .toList
    .flatMap(value =>
      List(deployWith(value), pauseWith(value), resumeWith(value), destroyWith(value)))
end DeployTools
