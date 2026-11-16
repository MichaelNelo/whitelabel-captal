package whitelabel.captal.mcp.tools

import java.time.Instant

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.advertiser.Error as AdvertiserError
import whitelabel.captal.core.application.commands.{
  CreateAdvertiserCommand,
  ReinstateAdvertiserCommand,
  RetireAdvertiserCommand,
  UpdateAdvertiserCommand
}
import whitelabel.captal.core.application.queries.ListAdvertisersQuery
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.queries.ListAdvertisers
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** The advertisers, through `core`'s commands and `ListAdvertisersQuery`: a name is unique because
  * the commands refuse a taken one, not because the schema does.
  */
object AdvertiserTools:
  final case class AdvertiserView(
      @description("What create_advertiser_video takes to attach a video to this advertiser.")
      id: String,
      @description("Display name. Unique, because nothing in the schema makes it so.")
      name: String,
      @description(
        "Rotation weight against the other active advertisers, as a share of their total. Zero means never drawn.")
      priority: Int,
      @description("A retired advertiser keeps its rows but is drawn by nothing.")
      active: Boolean,
      @description(
        "Videos of this advertiser currently in rotation. Zero with active true means the advertiser can be drawn and has nothing to show.")
      activeVideos: Int)
  final case class ListRequest(
      @description("Retired advertisers are hidden by default; set this to see them too.")
      includeInactive: Boolean = false)
  final case class ListResponse(
      @description("Sorted by name.")
      advertisers: List[AdvertiserView])

  final case class CreateRequest(
      @description(
        "Display name, and the identity this tool enforces: a name already in use is refused.")
      name: String,
      @description(
        "Rotation weight against the other active advertisers. Ten is the usual value; zero registers the advertiser without ever showing it.")
      priority: Int)
  final case class CreateResponse(
      @description("The new advertiser's id, which create_advertiser_video takes.")
      id: String,
      @description("The name as stored, trimmed.")
      name: String,
      @description("The rotation weight as stored.")
      priority: Int)

  final case class UpdateRequest(
      @description("Which advertiser to change. Call list_advertisers for the ids.")
      id: String,
      @description("New display name. Omit to leave it alone. Still has to be unique.")
      name: Option[String],
      @description("New rotation weight. Omit to leave it alone.")
      priority: Option[Int])
  final case class UpdateResponse(
      @description("The advertiser changed.")
      id: String,
      @description("The name after the change, whether or not it was the field updated.")
      name: String,
      @description("The rotation weight after the change.")
      priority: Int)

  final case class SetActiveRequest(
      @description("Which advertiser to retire or bring back.")
      id: String,
      @description(
        "False retires it. Nothing is deleted — answers already given stay attached to their questions — but the advertiser and its videos leave the rotation.")
      active: Boolean)
  final case class SetActiveResponse(
      @description("The advertiser changed.")
      id: String,
      @description("Its state after the change.")
      active: Boolean,
      @description(
        "Videos that went out of rotation with it, or came back. Reported because the blast radius is otherwise only visible in the portal.")
      activeVideos: Int)

  given Schema[AdvertiserView] = DeriveSchema.gen
  given Schema[ListRequest] = DeriveSchema.gen
  given Schema[ListResponse] = DeriveSchema.gen
  given Schema[CreateRequest] = DeriveSchema.gen
  given Schema[CreateResponse] = DeriveSchema.gen
  given Schema[UpdateRequest] = DeriveSchema.gen
  given Schema[UpdateResponse] = DeriveSchema.gen
  given Schema[SetActiveRequest] = DeriveSchema.gen
  given Schema[SetActiveResponse] = DeriveSchema.gen

  /** What the operator reads for each refusal of these tools. */
  private val describe: PartialFunction[AppError, List[String]] =
    case AppError.Advertiser(found) =>
      found
        .toList
        .map:
          case AdvertiserError.NotFound(id) =>
            s"No advertiser with id '$id'. Call list_advertisers to see what exists."
          case AdvertiserError.BlankName =>
            "Name must not be blank."
          case AdvertiserError.NegativePriority(priority) =>
            s"Priority must be zero or greater, got $priority."
          case AdvertiserError.NameTaken(name, by) =>
            s"An advertiser named '$name' already exists (id ${by
                .asString}). Use update_advertiser to change it."
          case AdvertiserError.NothingToUpdate =>
            "Nothing to update: give a name, a priority, or both."

  /** The advertiser `raw` names, or the operator's refusal for an id no advertiser can have. */
  private def idOf(raw: String): IO[String, AdvertiserId] = ZIO
    .fromOption(AdvertiserId.fromString(raw.trim))
    .orElseFail(s"No advertiser with id '$raw'. Call list_advertisers to see what exists.")

  /** Runs `command` through `flow` as the calling operator. */
  private def changed[C, R](ctx: McpToolContext, command: C)(
      flow: Flow.Aux[Task, C, R]): ZIO[OperatorContext, String, R] =
    for
      caller <- Commands.callerOf(ctx)
      result <- Commands.run(Operator(caller, None), command)(flow)(describe)
    yield result

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_advertisers")
    .description(
      "Advertisers with their rotation priority and how many active videos each one has. Inactive advertisers are hidden unless asked for.")
    .readOnly
    .handle: (in: ListRequest) =>
      for
        quill   <- ZIO.service[QuillSqlite]
        reports <-
          Commands.ask(ListAdvertisers(quill), ListAdvertisersQuery(in.includeInactive))(describe)
      yield ListResponse(
        reports.map(r => AdvertiserView(r.id, r.name, r.priority, r.active, r.activeVideos)))

  val create: McpToolHandlerR[OperatorContext & ContentFlows.CreateAdvertiser] = McpTool(
    "create_advertiser")
    .description(
      "Register an advertiser. Priority weights the video rotation against the other active advertisers; zero means never drawn. Names must be unique. Call read_skill('add-advertiser') first.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: CreateRequest, ctx: McpToolContext) =>
      for
        flow    <- ZIO.service[ContentFlows.CreateAdvertiser]
        created <-
          changed(
            ctx,
            CreateAdvertiserCommand(AdvertiserId.generate, in.name, in.priority, Instant.now))(flow)
      yield CreateResponse(created.id.asString, created.name, created.priority)

  val update: McpToolHandlerR[OperatorContext & ContentFlows.UpdateAdvertiser] = McpTool(
    "update_advertiser")
    .description("Change an advertiser's name or rotation priority. Omitted fields are left alone.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: UpdateRequest, ctx: McpToolContext) =>
      for
        id      <- idOf(in.id)
        flow    <- ZIO.service[ContentFlows.UpdateAdvertiser]
        updated <-
          changed(ctx, UpdateAdvertiserCommand(id, in.name, in.priority, Instant.now))(flow)
      yield UpdateResponse(updated.id.asString, updated.name, updated.priority)

  val setActive: McpToolHandlerR[
    QuillSqlite & OperatorContext & ContentFlows.RetireAdvertiser &
      ContentFlows.ReinstateAdvertiser] = McpTool("set_advertiser_active")
    .description(
      "Retire an advertiser or bring it back. Retiring also takes its videos out of the rotation, since the video query joins on the advertiser being active. See read_skill('add-advertiser').")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: SetActiveRequest, ctx: McpToolContext) =>
      for
        id <- idOf(in.id)
        now = Instant.now
        _ <-
          if in.active then
            ZIO.serviceWithZIO[ContentFlows.ReinstateAdvertiser](
              changed(ctx, ReinstateAdvertiserCommand(id, now))(_))
          else
            ZIO.serviceWithZIO[ContentFlows.RetireAdvertiser](
              changed(ctx, RetireAdvertiserCommand(id, now))(_))
        // Reported so the operator sees the blast radius rather than discovering it in the portal.
        videos <- ZIO.serviceWithZIO[QuillSqlite](
          ListAdvertisers.activeVideos(_, id.asString).orDie)
      yield SetActiveResponse(id.asString, in.active, videos)

  val all: List[McpToolHandlerR[
    QuillSqlite & OperatorContext & ContentFlows.CreateAdvertiser & ContentFlows.UpdateAdvertiser &
      ContentFlows.RetireAdvertiser & ContentFlows.ReinstateAdvertiser]] = List(
    list,
    create,
    update,
    setActive)
end AdvertiserTools
