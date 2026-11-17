package whitelabel.captal.mcp.tools

import java.util.UUID

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.StartOperationCommand
import whitelabel.captal.core.application.queries.{AssetsOfQuery, RunReport, RunStatusQuery}
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.core.operation.{Operation, RunId}
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.mcp.apps.{RunRoutes, RunTicket, RunUi}
import whitelabel.captal.mcp.aws.InfraStore
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.locks.LockUnavailable
import whitelabel.captal.platform.operations.{RunRecords, TransitionTaken}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.json.ast.Json
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.reads
import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** A location's portal in AWS: deploy, pause, resume and destroy, each a run of
  * [[StartOperationCommand]] that [[OperationRunner]] takes from there, and `get_deploy_status`,
  * which follows one.
  *
  * Each tool answers at once with the run, since the work takes minutes on AWS and a host keeps a
  * call open for less. Where `core` refuses because another run is going or was left part way, the
  * tool asks the operator — see its progress, finish it first — and runs what the answer leads to.
  */
object DeployTools:
  /** What the operation tools need from the runtime. */
  type Env =
    QuillSqlite & AssetStore & OperatorContext & OperationFlows.Start & Queries.RunStatus &
      Queries.AssetsOf

  final case class DeployRequest(
      @description(
        "Which location to stand up. It has to exist — call create_location first, since the slug is what the service, the target group and the ALB rule are all named after.")
      slug: String)

  final case class PauseRequest(
      @description("Which location to take out of service. It stays deployed and keeps its data.")
      slug: String)

  final case class DestroyRequest(
      @description("Which location's infrastructure to remove.")
      slug: String,
      @description(
        "The slug again, typed out. This is the confirmation: it has to match exactly, and the call is refused otherwise.")
      confirm: String)

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
      @description(
        "Every step in order, each with what it found — a revision, an invalidation, a count — or why it failed. Their names are what to show an operator.")
      steps: List[RunStep],
      startedAt: String,
      @description("When the work last reported in.")
      updatedAt: String,
      finishedAt: Option[String],
      @description("Why it failed, once it did.")
      error: Option[String],
      @description("What to do now.")
      nextSteps: List[String],
      @description(
        "For the page that shows this run, on the answer of the call that started it. Not for the model to use.")
      watch: Option[RunWatch])

  given Schema[DeployRequest] = DeriveSchema.gen
  given Schema[PauseRequest] = DeriveSchema.gen
  given Schema[DestroyRequest] = DeriveSchema.gen
  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[RunStep] = DeriveSchema.gen
  given Schema[RunWatch] = DeriveSchema.gen
  given Schema[RunView] = DeriveSchema.gen

  /** The token behind the call, which a run records as the one that started it. */
  private def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("Could not tell which token made this call.")

  private def slugOf(raw: String): IO[String, Slug] = Refusals.slugOf(raw)

  private def toolFor(operation: String): String = s"${operation}_location"

  /** Starts `operation` on the location, as a new run. */
  private def operate(
      ctx: McpToolContext,
      uiOrigin: String,
      slug: String,
      operation: Operation): ZIO[Env, String, RunView] =
    for
      place  <- slugOf(slug)
      caller <- callerOf(ctx)
      now    <- Clock.instant
      view   <- start(
        StartOperationCommand(place, operation, None, RunId(UUID.randomUUID.toString), caller, now),
        ctx,
        uiOrigin)
    yield view

  /** Runs the command as the calling operator, who can be asked; the run it started, or the one the
    * operator chose to follow or finish when `core` refused.
    */
  private def start(
      cmd: StartOperationCommand,
      ctx: McpToolContext,
      uiOrigin: String): ZIO[Env, String, RunView] = ZIO
    .serviceWithZIO[OperatorContext](
      _.locally(Operator(cmd.by, Some(Elicit.yesNo(ctx, _))))(
        ZIO.serviceWithZIO[OperationFlows.Start](_.execute(cmd))))
    .foldZIO(
      {
        case Flow.HandlerError(errors) =>
          refused(
            errors
              .toList
              .flatMap:
                case AppError.Location(found) =>
                  found.toList
                case _ =>
                  Nil
            ,
            cmd,
            ctx,
            uiOrigin)
        case TransitionTaken(_, holder) =>
          followOrNot(
            holder,
            cmd,
            ctx,
            uiOrigin,
            s"Another operation on '${cmd.slug.asString}' started at the same moment")
        case _: LockUnavailable =>
          ZIO.fail(
            s"Could not tell whether an operation is going on '${cmd
                .slug
                .asString}': not enough of the lock's Redis instances answered in time. Nothing was started; try again in a moment.")
        case other =>
          ZIO.die(other)
      },
      _ => viewFor(cmd.newRun, cmd.by, uiOrigin, alreadyGoing = false)
    )

  /** What a refusal leads to: a question, when it is one the operator can settle here, or the
    * refusal as the operator reads it.
    */
  private def refused(
      errors: List[LocationError],
      cmd: StartOperationCommand,
      ctx: McpToolContext,
      uiOrigin: String): ZIO[Env, String, RunView] =
    lazy val described = errors.map(Refusals.location).mkString(" ")
    errors.collectFirst {
      case unfinished: LocationError.PreviousOperationUnfinished =>
        unfinished
      case going: LocationError.OperationInProgress =>
        going
    } match
      case Some(LocationError.PreviousOperationUnfinished(run, operation, pending)) =>
        val at = pending.headOption.fold("")(step => s" at «${step.description}»")
        Elicit
          .yesNo(
            ctx,
            s"The ${operation.name} of '${cmd.slug.asString}' (run ${run
                .asString}) stopped$at, so the location stands half changed. Finish that ${operation
                .name} first? It takes only the steps it left. The ${cmd
                .operation
                .name} asked for is not started either way: ask for it again once this ends."
          )
          .flatMap:
            case Some(true) =>
              start(cmd.copy(operation = operation, finishing = Some(run)), ctx, uiOrigin)
            case _ =>
              ZIO.fail(described)
      case Some(LocationError.OperationInProgress(run, _, _)) =>
        followOrNot(run, cmd, ctx, uiOrigin, described)
      case _ =>
        ZIO.fail(described)
    end match
  end refused

  /** Asks whether to follow the run `run` that is going instead; its view when yes. */
  private def followOrNot(
      run: RunId,
      cmd: StartOperationCommand,
      ctx: McpToolContext,
      uiOrigin: String,
      why: String): ZIO[Env, String, RunView] = Elicit
    .yesNo(ctx, s"$why. Nothing new was started. See its progress?")
    .flatMap:
      case Some(true) =>
        viewFor(run, cmd.by, uiOrigin, alreadyGoing = true)
      case _ =>
        ZIO.fail(s"$why. Nothing new was started; get_deploy_status follows run ${run.asString}.")

  /** The run's view, with what the page in [[RunUi]] needs to follow it. */
  private def viewFor(
      run: RunId,
      caller: String,
      uiOrigin: String,
      alreadyGoing: Boolean): ZIO[QuillSqlite & Queries.RunStatus, String, RunView] =
    for
      found <-
        ZIO.serviceWithZIO[Queries.RunStatus](_.execute(RunStatusQuery.ById(run.asString))).orDie
      report <- ZIO.fromEither(found).mapError(_ => s"Run ${run.asString} is not recorded.")
      token  <- Tokens
        .byIdentifier(caller)
        .someOrFail("The token that made this call no longer exists.")
    yield viewOf(report, alreadyGoing).copy(watch = Some(
      RunWatch(
        ticket = RunTicket.mint(token.tokenSha256, caller, report.id),
        streamUrl = s"$uiOrigin${RunRoutes.EventsPath}")))

  private[mcp] def viewOf(report: RunReport, alreadyGoing: Boolean): RunView =
    val stoppedAt = report
      .steps
      .find(step => step.state == RunRecords.StepFailed || step.state == RunRecords.StepRunning)
      .map(_.name)
    val at = stoppedAt.fold("")(name => s" at '$name'")
    val next =
      report.status match
        case RunRecords.Running if alreadyGoing =>
          List(
            s"A ${report.operation} of '${report
                .slug}' was already running, so this call started nothing; this is that run. Follow it with get_deploy_status.")
        case RunRecords.Running =>
          List(
            "Running in the background, and it does not need this conversation to stay open. Call get_deploy_status with this runId about every 30 seconds until status is no longer running.")
        case RunRecords.Succeeded =>
          done(report.operation)
        case RunRecords.Interrupted =>
          List(
            s"The server doing this stopped before it finished$at. Call ${toolFor(
                report
                  .operation)} again: it offers to finish this run, taking only the steps it left.")
        case _ =>
          List(
            s"Failed$at. Once the cause is dealt with, call ${toolFor(
                report.operation)} again: it offers to finish this run from the step that failed.")
    RunView(
      runId = report.id,
      slug = report.slug,
      operation = report.operation,
      status = report.status,
      steps = report.steps.map(step => RunStep(step.name, step.state, step.detail)),
      startedAt = report.startedAt,
      updatedAt = report.updatedAt,
      finishedAt = report.finishedAt,
      error = report.error,
      nextSteps = next,
      watch = None
    )
  end viewOf

  /** What is left once the operation succeeded; what each step found is on the step. */
  private def done(operation: String): List[String] =
    operation match
      case "deploy" =>
        List(
          "The service is serving. Call get_location_readiness to see whether the content is complete: a deploy stands up infrastructure and writes no content.",
          "A new image or bundle reaches this location through the release, not through another deploy."
        )
      case "pause" =>
        List(
          "The edge serves the maintenance page for this location, and the API answers the paused JSON. A guest already inside the SPA reads it on their next call and redirects themselves.",
          "Nothing the operator authored was touched — the stylesheet, the icon and the videos are as they were. resume_location puts the page and the API back."
        )
      case "resume" =>
        List(
          "The service has a task again, the rule forwards to it, and the location's own page is back at the edge, as it was before the pause.")
      case _ =>
        List(
          "The location's infrastructure is gone. Its log group was kept, because it holds why this was torn down.",
          "The database is untouched: the location, its translations, surveys and videos are all still there, and list_locations still reports it. deploy_location stands it up again.",
          "If its S3 objects were deleted, the video rows survive pointing at files that are gone: every video needs uploading again, or retiring with set_video_active."
        )

  /** The tool's declaration on its own, for a test that needs no store. */
  private[mcp] val declaration: McpTool = McpTool("deploy_location")
    .description(
      "Stand up a location's portal in AWS: copy the bundle, then the log group, target group, ALB listener rule, task definition and ECS service, and wait for it to be serving. For a location created or destroyed; one already deployed is refused, since new images and bundles reach every location through the release. Needs the terms of service and an access point. Answers at once with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDeploy)

  private def deployWith(uiOrigin: String): McpToolHandlerR[Env] = declaration
    .handleWithContext: (in: DeployRequest, ctx: McpToolContext) =>
      operate(ctx, uiOrigin, in.slug, Operation.Deploy)
    .withUi(RunUi.AppUri)

  private def pauseWith(uiOrigin: String): McpToolHandlerR[Env] = McpTool("pause_location")
    .description(
      "Take a deployed location's portal out of service without dismantling it. In order: the maintenance page replaces the location's index.html at the edge, the CloudFront invalidation is waited out, and only then is the API stopped — the ALB answers a fixed 'paused' JSON and the ECS service is scaled to zero. resume_location puts every part of it back. Answers at once with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDeploy)
    .handleWithContext: (in: PauseRequest, ctx: McpToolContext) =>
      operate(ctx, uiOrigin, in.slug, Operation.Pause)
    .withUi(RunUi.AppUri)

  private def resumeWith(uiOrigin: String): McpToolHandlerR[Env] = McpTool("resume_location")
    .description(
      "Put a paused location back in service, in the reverse order of the pause: the service is scaled back to one task and the ALB rule forwards again, then the location's own page replaces the maintenance one at the edge. Needs the terms of service and an access point no other deployed location takes guests through. Answers at once with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDeploy)
    .handleWithContext: (in: PauseRequest, ctx: McpToolContext) =>
      operate(ctx, uiOrigin, in.slug, Operation.Resume)
    .withUi(RunUi.AppUri)

  /** Asks about the S3 objects before the command, whose argument the answer is: asked after, it
    * would hold the location's lock while a person answers.
    */
  private def destroyWith(uiOrigin: String): McpToolHandlerR[Env] = McpTool("destroy_location")
    .description(
      "Remove everything a location has in AWS, whatever state it is in — a deploy that failed part way included: the ECS service, its listener rules, its target group and its task definition revisions — and, if the operator says yes when asked, its entire S3 prefix: the bundle copy, the stylesheet, the icon and the uploaded video files. Requires the slug typed again as confirmation. Keeps the log group and touches nothing in the database, so deploy_location can stand the portal up again. For temporary downtime use pause_location, which deletes nothing. Asks about the S3 objects in the call, then answers with a run and keeps working in the background; get_deploy_status follows it. Call read_skill('deploy-location') first.")
    .requireScopes(Auth.InfraDestroy)
    .annotations(destructive = OptBool.True, idempotent = OptBool.True)
    .handleWithContext: (in: DestroyRequest, ctx: McpToolContext) =>
      for
        _ <-
          ZIO.unless(in.confirm == in.slug):
            ZIO.fail(
              s"Refused: `confirm` has to be '${in.slug}' exactly, and it was '${in
                  .confirm}'. This removes the service, the rule, the target group and every task definition revision for the location.")
        found <- ZIO
          .serviceWithZIO[Queries.AssetsOf](_.execute(AssetsOfQuery(in.slug)))
          .mapError(e => s"Could not count the location's S3 objects: ${e.getMessage}")
        assets <- ZIO.fromEither(found).mapError(Refusals.describe)
        wanted <-
          if assets.objects == 0 then
            ZIO.succeed(false)
          else
            deleteAssets(ctx, assets.objects, assets.videos)
        view <- operate(ctx, uiOrigin, in.slug, Operation.Destroy(deleteAssets = wanted))
      yield view
    .withUi(RunUi.AppUri)

  /** Only a yes deletes: an unanswered question — declined, or a host that cannot ask — keeps the
    * objects, so the one irreversible step is the one that needs something to actively say so.
    */
  private def deleteAssets(ctx: McpToolContext, objects: Int, videos: Int): UIO[Boolean] = ctx
    .elicit(
      s"Also delete this location's $objects objects from S3? That includes $videos uploaded video file${
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
    .map(
      _.exists(answered =>
        answered.action == "accept" &&
          !answered.content.flatMap(_.get("deleteAssets")).flatMap(_.asBoolean).contains(false)))

  private val status: McpToolHandlerR[Queries.RunStatus] =
    McpTool("get_deploy_status")
      .description(
        "Where a deploy, pause, resume or destroy of a location's portal stands: every step, its state and what it found, and what is left once it ended. Those four answer at once and leave the work running in the background, so call this about every 30 seconds until status is no longer running. Pass the runId they answered with, or a slug for that location's latest run. Call read_skill('deploy-location') first.")
      .readOnly
      .reads((in: StatusRequest) =>
        (in.runId, in.slug) match
          case (Some(id), _) =>
            ZIO.succeed(RunStatusQuery.ById(id))
          case (None, Some(slug)) =>
            ZIO.succeed(RunStatusQuery.LatestOf(slug))
          case (None, None) =>
            ZIO.fail("Pass a runId, or a slug for its latest run."))((_, report: RunReport) =>
        ZIO.succeed(viewOf(report, alreadyGoing = false)))

  /** All five tools, or none: registered only when the deploy configuration exists, which is also
    * what the runner that takes their runs needs.
    *
    * @param uiOrigin
    *   where this server is reached, which the run page streams from.
    */
  def all(store: Option[InfraStore], uiOrigin: String): List[McpToolHandlerR[Env]] = store
    .toList
    .flatMap(_ =>
      List(
        deployWith(uiOrigin),
        pauseWith(uiOrigin),
        resumeWith(uiOrigin),
        destroyWith(uiOrigin),
        status))
end DeployTools
