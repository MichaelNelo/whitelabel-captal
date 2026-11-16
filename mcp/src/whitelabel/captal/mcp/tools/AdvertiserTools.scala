package whitelabel.captal.mcp.tools

import cats.data.NonEmptyChain
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.advertiser.{Advertiser, Error as AdvertiserError}
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.commands.{
  CreateAdvertiserCommand,
  ReinstateAdvertiserCommand,
  RetireAdvertiserCommand,
  UpdateAdvertiserCommand
}
import whitelabel.captal.core.application.queries.{AdvertiserReport, ListAdvertisersQuery}
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.OperatorContext
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.{reads, runs, runsEither}
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

  /** The advertiser `raw` names, refused as an id no advertiser can have is. */
  private def idOf(raw: String): IO[String, AdvertiserId] = ZIO
    .fromOption(AdvertiserId.fromString(raw.trim))
    .orElseFail(
      Refusals.describe(AppError.Advertiser(NonEmptyChain.one(AdvertiserError.NotFound(raw)))))

  val list: McpToolHandlerR[Queries.ListAdvertisers] =
    McpTool("list_advertisers")
      .description(
        "Advertisers with their rotation priority and how many active videos each one has. Inactive advertisers are hidden unless asked for.")
      .readOnly
      .reads((in: ListRequest) => ZIO.succeed(ListAdvertisersQuery(in.includeInactive))):
        (_, reports: List[AdvertiserReport]) =>
          ZIO.succeed(
            ListResponse(
              reports.map(r => AdvertiserView(r.id, r.name, r.priority, r.active, r.activeVideos))))

  val create: McpToolHandlerR[OperatorContext & ContentFlows.CreateAdvertiser] =
    McpTool("create_advertiser")
      .description(
        "Register an advertiser. Priority weights the video rotation against the other active advertisers; zero means never drawn. Names must be unique. Call read_skill('add-advertiser') first.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: CreateRequest) =>
        Clock.instant.map(CreateAdvertiserCommand(AdvertiserId.generate, in.name, in.priority, _))):
        (_, created: Advertiser) =>
          ZIO.succeed(CreateResponse(created.id.asString, created.name, created.priority))

  val update: McpToolHandlerR[OperatorContext & ContentFlows.UpdateAdvertiser] =
    McpTool("update_advertiser")
      .description(
        "Change an advertiser's name or rotation priority. Omitted fields are left alone.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: UpdateRequest) =>
        for
          id  <- idOf(in.id)
          now <- Clock.instant
        yield UpdateAdvertiserCommand(id, in.name, in.priority, now)): (_, updated: Advertiser) =>
        ZIO.succeed(UpdateResponse(updated.id.asString, updated.name, updated.priority))

  val setActive: McpToolHandlerR[
    Queries.ListAdvertisers & OperatorContext & ContentFlows.RetireAdvertiser &
      ContentFlows.ReinstateAdvertiser] =
    McpTool("set_advertiser_active")
      .description(
        "Retire an advertiser or bring it back. Retiring also takes its videos out of the rotation, since the video query joins on the advertiser being active. See read_skill('add-advertiser').")
      .requireScopes(Auth.ContentWrite)
      .runsEither((in: SetActiveRequest) =>
        for
          id  <- idOf(in.id)
          now <- Clock.instant
        yield
          if in.active then
            Right(ReinstateAdvertiserCommand(id, now))
          else
            Left(RetireAdvertiserCommand(id, now))): (in, _: Unit) =>
        // Reported so the operator sees the blast radius rather than discovering it in the portal.
        for
          id      <- idOf(in.id)
          reports <- ZIO.serviceWithZIO[Queries.ListAdvertisers](
            Commands.ask(_, ListAdvertisersQuery(includeInactive = true))())
        yield SetActiveResponse(
          id.asString,
          in.active,
          reports.find(_.id == id.asString).fold(0)(_.activeVideos))

  val all: List[McpToolHandlerR[
    Queries.ListAdvertisers & OperatorContext & ContentFlows.CreateAdvertiser &
      ContentFlows.UpdateAdvertiser & ContentFlows.RetireAdvertiser &
      ContentFlows.ReinstateAdvertiser]] = List(list, create, update, setActive)
end AdvertiserTools
