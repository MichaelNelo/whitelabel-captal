package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.{
  RemoveAccessPointCommand,
  RemoveControllerCommand,
  UpdateAccessPointCommand,
  UpdateControllerCommand
}
import whitelabel.captal.core.application.queries.{
  AccessPointsOfQuery,
  AccessPointsQuery,
  AccessPointsReport,
  ControllerReport,
  ControllersQuery,
  Taker
}
import whitelabel.captal.core.location.{AccessPoint, Controller, ControllerId, SettingsChange}
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.mcp.apps.{UiRoutes, UiTicket, UnifiUi}
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.{reads, runs}
import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** A location's UniFi: each access point it is configured with, the controller that grants access
  * through it, and what the portal does for the guests who join through it — configured through a
  * page so that no token reaches the model: the operator types it in and the browser posts it to
  * [[UiRoutes]]. And the system's controllers, which any access point of any location can answer
  * to: listed, moved and removed here, their tokens set only through the page.
  */
object UnifiTools:

  final case class ConfigureRequest(
      @description("Which location to configure. Call list_locations for the slugs.")
      slug: String,
      @description(
        "The access point to change, by MAC, in any spelling: the page opens with its settings. Omitted, the page configures the location with another one, the system's or a new one.")
      apMac: Option[String] = None)

  @description("A UniFi controller the system has, without its token.")
  final case class ControllerView(
      @description("Its id: what update_access_point and the page choose it by.")
      id: String,
      @description("Its host name or IP.")
      host: String,
      @description("Its port; 443 when unset.")
      port: Option[Int],
      @description("The UniFi site id on it.")
      siteId: String,
      @description("Whether a token is stored. The value itself is never returned.")
      tokenSet: Boolean,
      @description(
        "How many access points a location is configured with answer to it. One with none can be removed.")
      accessPoints: Int)

  @description("The location that took an access point: its guests are that location's.")
  final case class TakerView(
      @description("The location's slug.")
      slug: String,
      @description(
        "'deploying' while the deploy or the resume that took it runs; 'deployed' once it succeeded.")
      state: String)

  @description("An access point the system has, whichever locations are configured with it.")
  final case class KnownAccessPointView(
      @description("Its MAC, lowercase with colons.")
      mac: String,
      @description("The controller it answers to, for every location configured with it.")
      controllerId: String,
      @description("The locations configured with it.")
      locations: List[String],
      @description("The location that took it, if one has.")
      takenBy: Option[TakerView])

  /** Reaches the page as the result of the call that opened it, and is mirrored by
    * `UnifiApp.Config`. Everything here is either already public or a short-lived ticket; the token
    * travels in the other direction and never appears in this type.
    */
  final case class ConfigureResponse(
      @description(
        "Authorises the page's write, for this location only, for a few minutes. Not a credential and not reusable elsewhere.")
      ticket: String,
      @description("Where the page posts to. Absolute, because the iframe's own origin is opaque.")
      postUrl: String,
      @description("The location being configured.")
      slug: String,
      @description("Its display name, shown on the page.")
      name: String,
      @description("The access point the page opens with; none for another one.")
      apMac: Option[String],
      @description("The controller of the access point the page opens with.")
      controllerId: Option[String],
      redirectUrl: Option[String],
      durationMinutes: Option[Int],
      @description("The system's controllers, which the page offers for a new access point.")
      controllers: List[ControllerView],
      @description(
        "The system's access points, which the page offers: one of them answers to its own controller.")
      knownAccessPoints: List[KnownAccessPointView])

  final case class StatusRequest(
      @description("Which location to report on.")
      slug: String)

  final case class AccessPointView(
      @description("Its MAC, lowercase with colons: what a guest's redirect names it by.")
      apMac: String,
      @description("The controller it answers to: list_unifi_controllers lists them.")
      controllerId: String,
      @description("Its controller's host name or IP.")
      host: String,
      @description("Its controller's port; 443 when unset.")
      port: Option[Int],
      @description("The UniFi site id on its controller.")
      siteId: String,
      @description("Whether its controller has a token stored. The value itself is never returned.")
      tokenSet: Boolean,
      @description(
        "Minutes of access each guest who joins through it here is granted; 1440 when unset.")
      durationMinutes: Option[Int],
      @description(
        "Where the load balancer sends the guests who join through it instead of the portal, for a portal hosted elsewhere; unset, they go to the portal. Not used while the location is paused.")
      redirectUrl: Option[String],
      @description(
        "False means the portal never calls its controller, whatever is configured: its guests finish the flow and stay at Ready.")
      unifiEnabled: Boolean,
      @description(
        "The location that took it, this one or another: a location takes its access points as its deploy or resume starts, and lets them go once paused or destroyed. A deploy through one another location took is refused.")
      takenBy: Option[TakerView])

  /** Status without the tokens. Reports whether each access point is configured, not its token. */
  final case class StatusResponse(
      @description("The location this describes.")
      slug: String,
      @description(
        "False means it has no access point, so every guest is turned away with an error: the AP a guest comes through is what says which controller grants them access. Configure one with configure_unifi.")
      configured: Boolean,
      @description(
        "The access points it is configured with, each with its controller and settings. A guest is admitted only through one of these.")
      accessPoints: List[AccessPointView])

  final case class UpdateAccessPointRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "Which of its access points, by MAC, in any spelling. get_unifi_config lists them.")
      apMac: String,
      @description(
        "The MAC of the access point it is configured with instead, in any spelling, for an AP that was replaced; one the system has keeps its own controller. Left as it is when omitted.")
      newApMac: Option[String] = None,
      @description(
        "The controller the access point answers to, for every location configured with it, by id. list_unifi_controllers lists them. Left as it is when omitted.")
      controllerId: Option[String] = None,
      @description(
        "Minutes of access each guest who joins through it here is granted. Left as it is when omitted.")
      durationMinutes: Option[Int] = None,
      @description(
        "Where the load balancer sends the guests who join through it instead of the portal, an http or https URL, for a portal hosted elsewhere. Not where a guest goes after authorizing. An empty string removes it, and its guests go to the portal. Left as it is when omitted.")
      redirectUrl: Option[String] = None,
      @description(
        "False stops every call to its controller from this location; true resumes them. Its stored settings are kept either way. Left as it is when omitted.")
      unifiEnabled: Option[Boolean] = None)

  final case class RemoveAccessPointRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "Which of its access points, by MAC, in any spelling. get_unifi_config lists them.")
      apMac: String)

  final case class ListControllersRequest()

  final case class ControllersResponse(
      @description("The system's controllers, without their tokens.")
      controllers: List[ControllerView])

  final case class UpdateControllerRequest(
      @description("Which controller, by id. list_unifi_controllers lists them.")
      controllerId: String,
      @description("Its host name or IP. Left as it is when omitted.")
      host: Option[String] = None,
      @description("Its port, 1 to 65535. Left as it is when omitted.")
      port: Option[Int] = None,
      @description("The UniFi site id on it. Left as it is when omitted.")
      siteId: Option[String] = None)

  final case class RemoveControllerRequest(
      @description("Which controller, by id. list_unifi_controllers lists them.")
      controllerId: String)

  given Schema[ConfigureRequest] = DeriveSchema.gen
  given Schema[ControllerView] = DeriveSchema.gen
  given Schema[TakerView] = DeriveSchema.gen
  given Schema[KnownAccessPointView] = DeriveSchema.gen
  given Schema[ConfigureResponse] = DeriveSchema.gen
  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[AccessPointView] = DeriveSchema.gen
  given Schema[StatusResponse] = DeriveSchema.gen
  given Schema[UpdateAccessPointRequest] = DeriveSchema.gen
  given Schema[RemoveAccessPointRequest] = DeriveSchema.gen
  given Schema[ListControllersRequest] = DeriveSchema.gen
  given Schema[ControllersResponse] = DeriveSchema.gen
  given Schema[UpdateControllerRequest] = DeriveSchema.gen
  given Schema[RemoveControllerRequest] = DeriveSchema.gen

  /** @param origin
    *   Where this server is reached, used to build an absolute `postUrl`. The CSP that lets the
    *   browser actually reach it is declared on the page rather than here — see
    *   [[EntityResources]]; the host reads it from the resource and ignores anything the tool says
    *   about it.
    */
  def configure(origin: String): McpToolHandlerR[
    QuillSqlite & Queries.AccessPointsOf & Queries.Controllers & Queries.KnownAccessPoints] =
    McpTool("configure_unifi")
      .description(
        "Open the form that configures a location with one of its access points — one the system has, which answers to its own controller, or a new one, with a controller the system has or a new one with its host, port, site id and API token — and the access duration and redirect URL of the guests who join through it there, or changes them; and that replaces a controller's token. Takes no token on purpose: it must not travel through the conversation, so the operator types it into the page and the browser sends it straight to the server. A guest is admitted only through one of the location's access points, so a location without any turns every guest away. Any number of locations can be configured with one access point; only one takes its guests at a time, the one deployed.")
      .requireScopes(Auth.ContentWrite)
      // Needs the context, not just the arguments: the ticket is signed with the calling token's
      // own stored hash, so that revoking that token kills the pages it opened and issuing
      // anybody else's leaves them alone.
      .handleWithContext: (in: ConfigureRequest, ctx: McpToolContext) =>
        for
          current <- UiRoutes.currentFor(in.slug.trim)
          mac     <-
            ZIO.foreach(in.apMac.map(_.trim).filter(_.nonEmpty))(raw =>
              ZIO.fromEither(UiRoutes.macOf(raw)))
          point <-
            ZIO.foreach(mac)(mac =>
              ZIO
                .fromOption(current.accessPoints.find(_.apMac == mac))
                .orElseFail(
                  s"'${current
                      .slug}' no tiene un AP con la MAC $mac. Sin apMac, la página la configura con otro."))
          controllers <- controllersNow
          known       <- ZIO.serviceWithZIO[Queries.KnownAccessPoints](
            Commands.ask(_, AccessPointsQuery())())
          tokenId <- callerOf(ctx)
          row     <- Tokens
            .byIdentifier(tokenId)
            .someOrFail("El token que hizo la llamada ya no existe.")
          now <- Clock.instant
        yield ConfigureResponse(
          ticket = UiTicket.mint(row.tokenSha256, tokenId, current.slug, now),
          postUrl = s"$origin${UiRoutes.UnifiPath}",
          slug = current.slug,
          name = current.name,
          apMac = point.map(_.apMac),
          controllerId = point.map(_.controllerId),
          redirectUrl = point.flatMap(_.redirectUrl),
          durationMinutes = point.flatMap(_.durationMinutes),
          controllers = controllers,
          knownAccessPoints = known.map(point =>
            KnownAccessPointView(
              point.mac,
              point.controllerId,
              point.locations,
              point.takenBy.map(takerOf)))
        )
      .withUi(UnifiUi.AppUri)

  def status(): McpToolHandlerR[Queries.AccessPointsOf] =
    McpTool("get_unifi_config")
      .description(
        "A location's UniFi: each access point it is configured with, with its controller, without the tokens; its access duration, redirect URL and switch there; and the location that took it. Worth checking before a deploy: a guest is admitted only through one of its access points, so a location without any turns every guest away, and a deploy through one another location took is refused.")
      .readOnly
      .reads((in: StatusRequest) => Refusals.slugOf(in.slug).map(AccessPointsOfQuery(_)))(
        (_, report: AccessPointsReport) => ZIO.succeed(statusOf(report)))

  /** The access points as they stand after a change, as `get_unifi_config` answers them. */
  private def stored(slug: String): ZIO[Queries.AccessPointsOf, String, StatusResponse] =
    for
      parsed <- Refusals.slugOf(slug)
      report <- ZIO.serviceWithZIO[Queries.AccessPointsOf](
        Commands.ask(_, AccessPointsOfQuery(parsed))())
    yield statusOf(report)

  def updateAccessPoint(): McpToolHandlerR[
    Queries.AccessPointsOf & OperatorContext & LocationFlows.UpdateAccessPoint] =
    McpTool("update_access_point")
      .description(
        "Change how a location uses one of its access points without opening the form: the access point it is configured with instead, for an AP that was replaced; the controller the access point answers to, for every location configured with it; and the access duration, redirect URL and UniFi switch of the guests who join through it there. Each field given replaces the stored one; every field left out stays as it is. A controller's token is set only through configure_unifi, and where it is through update_unifi_controller. A new MAC another location took is refused while this one is deployed. Read when a guest arrives or is authorized, so it applies with no redeploy.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: UpdateAccessPointRequest) =>
        for
          slug   <- Refusals.slugOf(in.slug)
          mac    <- ZIO.fromEither(UiRoutes.parsedMac(in.apMac))
          newMac <- ZIO.foreach(in.newApMac)(raw => ZIO.fromEither(UiRoutes.parsedMac(raw)))
          _      <-
            ZIO.when(
              in.newApMac.isEmpty && in.controllerId.isEmpty && in.durationMinutes.isEmpty &&
                in.redirectUrl.isEmpty && in.unifiEnabled.isEmpty)(
              ZIO.fail(
                "No hay nada que cambiar: indicá newApMac, controllerId, durationMinutes, redirectUrl o unifiEnabled."))
          now <- Clock.instant
        yield UpdateAccessPointCommand(
          slug,
          mac,
          newMac,
          in.controllerId.map(id => ControllerId(id.trim)),
          SettingsChange(
            durationMinutes = in.durationMinutes.map(Some(_)),
            // An empty string removes it.
            redirectUrl = in.redirectUrl.map(url => Option(url.trim).filter(_.nonEmpty)),
            unifiEnabled = in.unifiEnabled
          ),
          now
        ))((in, _: AccessPoint) => stored(in.slug))

  def removeAccessPoint(): McpToolHandlerR[
    Queries.AccessPointsOf & OperatorContext & LocationFlows.RemoveAccessPoint] =
    McpTool("remove_access_point")
      .description(
        "Take an access point off a location, with its settings there. The access point stays the system's, answering to its controller, for any other location configured with it or configured with it later. A guest who comes through it afterwards is turned away with an error, unless another location takes it; one already admitted keeps their access until it runs out, and its revoke is skipped. A deployed location's last access point is refused: without it every guest would be turned away, so pause the location first.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: RemoveAccessPointRequest) =>
        for
          slug <- Refusals.slugOf(in.slug)
          mac  <- ZIO.fromEither(UiRoutes.parsedMac(in.apMac))
          now  <- Clock.instant
        yield RemoveAccessPointCommand(slug, mac, now))((in, _: Unit) => stored(in.slug))

  def listControllers(): McpToolHandlerR[Queries.Controllers] =
    McpTool("list_unifi_controllers")
      .description(
        "The system's UniFi controllers, without their tokens: where each is, and how many access points answer to it. Any access point of any location can answer to one; configure_unifi offers them for a new access point.")
      .readOnly
      .reads((_: ListControllersRequest) => ZIO.succeed(ControllersQuery()))(
        (_, controllers: List[ControllerReport]) =>
          ZIO.succeed(ControllersResponse(controllers.map(controllerOf))))

  def updateController()
      : McpToolHandlerR[Queries.Controllers & OperatorContext & LocationFlows.UpdateController] =
    McpTool("update_unifi_controller")
      .description(
        "Change where a UniFi controller is — its host, port and site id — for every access point that answers to it. Each field given replaces the stored one; every field left out stays as it is. Its token is replaced only through configure_unifi, so that it never travels through the conversation. An address another controller is at is refused: choose that one instead.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: UpdateControllerRequest) =>
        for
          _ <-
            ZIO.when(in.host.isEmpty && in.port.isEmpty && in.siteId.isEmpty)(
              ZIO.fail("No hay nada que cambiar: indicá host, port o siteId."))
          now <- Clock.instant
        yield UpdateControllerCommand(
          ControllerId(in.controllerId.trim),
          in.host,
          in.port,
          in.siteId,
          None,
          now))((_, changed: Controller) =>
        controllersNow.map(all => ControllersResponse(all.filter(_.id == changed.id.asString))))

  def removeController()
      : McpToolHandlerR[Queries.Controllers & OperatorContext & LocationFlows.RemoveController] =
    McpTool("remove_unifi_controller")
      .description(
        "Remove a UniFi controller the system has, with its token, and the access points that answer to it that no location is configured with. Refused while a location is configured with one that does: move each to another controller with update_access_point first, or take it off every location.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: RemoveControllerRequest) =>
        Clock.instant.map(now => RemoveControllerCommand(ControllerId(in.controllerId.trim), now)))(
        (_, _: Unit) => controllersNow.map(ControllersResponse(_)))

  private val controllersNow: ZIO[Queries.Controllers, String, List[ControllerView]] = ZIO
    .serviceWithZIO[Queries.Controllers](Commands.ask(_, ControllersQuery())())
    .map(_.map(controllerOf))

  private def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("No pude identificar el token que hizo la llamada.")

  private def controllerOf(report: ControllerReport): ControllerView = ControllerView(
    report.id,
    report.host,
    report.port,
    report.siteId,
    report.tokenSet,
    report.accessPoints)

  private def takerOf(taker: Taker): TakerView = TakerView(taker.slug, taker.state)

  private def statusOf(report: AccessPointsReport): StatusResponse = StatusResponse(
    slug = report.slug,
    configured = report.accessPoints.nonEmpty,
    accessPoints = report
      .accessPoints
      .map(point =>
        AccessPointView(
          point.apMac,
          point.controllerId,
          point.host,
          point.port,
          point.siteId,
          point.tokenSet,
          point.durationMinutes,
          point.redirectUrl,
          point.enabled,
          point.takenBy.map(takerOf)
        ))
  )

  def all(origin: String): List[McpToolHandlerR[
    QuillSqlite & Queries.AccessPointsOf & Queries.Controllers & Queries.KnownAccessPoints &
      OperatorContext & LocationFlows.UpdateAccessPoint & LocationFlows.RemoveAccessPoint &
      LocationFlows.UpdateController & LocationFlows.RemoveController]] = List(
    configure(origin),
    status(),
    updateAccessPoint(),
    removeAccessPoint(),
    listControllers(),
    updateController(),
    removeController())
end UnifiTools
