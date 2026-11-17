package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.{
  RemoveAccessPointCommand,
  UpdateAccessPointCommand
}
import whitelabel.captal.core.application.queries.{AccessPointsOfQuery, AccessPointsReport}
import whitelabel.captal.core.location.{AccessPoint, SettingsChange}
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

/** A location's UniFi: each of its access points, with the controller that grants access through it
  * and what the portal does for the guests who join through it — configured through a page so that
  * no token reaches the model: the operator types it in and the browser posts it to [[UiRoutes]].
  */
object UnifiTools:

  final case class ConfigureRequest(
      @description("Which location to configure. Call list_locations for the slugs.")
      slug: String,
      @description(
        "The access point to change, by MAC, in any spelling: the page opens with its settings. Omitted, the page registers a new one.")
      apMac: Option[String] = None)

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
      @description("Whether the access point already has a token. Blank on the form keeps it.")
      tokenSet: Boolean,
      host: Option[String],
      port: Option[Int],
      siteId: Option[String],
      @description("The access point the page opens with; none for a new one.")
      apMac: Option[String],
      redirectUrl: Option[String],
      durationMinutes: Option[Int])

  final case class StatusRequest(
      @description("Which location to report on.")
      slug: String)

  final case class AccessPointView(
      @description("Its MAC, lowercase with colons: what a guest's redirect names it by.")
      apMac: String,
      @description("Its controller's host name or IP.")
      host: String,
      @description("Its controller's port; 443 when unset.")
      port: Option[Int],
      @description("The UniFi site id on its controller.")
      siteId: String,
      @description("Whether a token is stored. The value itself is never returned.")
      tokenSet: Boolean,
      @description("Minutes of access each guest who joins through it is granted; 1440 when unset.")
      durationMinutes: Option[Int],
      @description(
        "Where the portal dispatcher sends the guests who join through it instead of the portal, for a portal hosted elsewhere; unset, they go to the portal. Not used while the location is paused.")
      redirectUrl: Option[String],
      @description(
        "False means the portal never calls its controller, whatever is configured: its guests finish the flow and stay at Ready.")
      unifiEnabled: Boolean)

  /** Status without the tokens. Reports whether each access point is configured, not its token. */
  final case class StatusResponse(
      @description("The location this describes.")
      slug: String,
      @description(
        "False means it has no access point, so every guest is turned away with an error: the AP a guest comes through is what says which controller grants them access. Register one with configure_unifi.")
      configured: Boolean,
      @description(
        "Its access points, each with its own controller and settings. A guest is admitted only through one of these.")
      accessPoints: List[AccessPointView])

  final case class UpdateAccessPointRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "Which of its access points, by MAC, in any spelling. get_unifi_config lists them.")
      apMac: String,
      @description(
        "Its MAC after the change, in any spelling, for an AP that was replaced. Left as it is when omitted.")
      newApMac: Option[String] = None,
      @description("Its controller's host name or IP. Left as it is when omitted.")
      host: Option[String] = None,
      @description("Its controller's port, 1 to 65535. Left as it is when omitted.")
      port: Option[Int] = None,
      @description("The UniFi site id on its controller. Left as it is when omitted.")
      siteId: Option[String] = None,
      @description(
        "Minutes of access each guest who joins through it is granted. Left as it is when omitted.")
      durationMinutes: Option[Int] = None,
      @description(
        "Where the portal dispatcher sends the guests who join through it instead of the portal, an http or https URL, for a portal hosted elsewhere. Not where a guest goes after authorizing. An empty string removes it, and its guests go to the portal. Left as it is when omitted.")
      redirectUrl: Option[String] = None,
      @description(
        "False stops every call to its controller; true resumes them. Its stored settings are kept either way. Left as it is when omitted.")
      unifiEnabled: Option[Boolean] = None)

  final case class RemoveAccessPointRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "Which of its access points, by MAC, in any spelling. get_unifi_config lists them.")
      apMac: String)

  given Schema[ConfigureRequest] = DeriveSchema.gen
  given Schema[ConfigureResponse] = DeriveSchema.gen
  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[AccessPointView] = DeriveSchema.gen
  given Schema[StatusResponse] = DeriveSchema.gen
  given Schema[UpdateAccessPointRequest] = DeriveSchema.gen
  given Schema[RemoveAccessPointRequest] = DeriveSchema.gen

  /** @param origin
    *   Where this server is reached, used to build an absolute `postUrl`. The CSP that lets the
    *   browser actually reach it is declared on the page rather than here — see
    *   [[EntityResources]]; the host reads it from the resource and ignores anything the tool says
    *   about it.
    */
  def configure(origin: String): McpToolHandlerR[QuillSqlite & Queries.AccessPointsOf] = McpTool(
    "configure_unifi")
    .description(
      "Open the form that registers one of a location's access points with the UniFi controller that grants access through it — host, port, site id and API token — and the access duration and redirect URL of the guests who join through it, or changes one. Takes no token on purpose: it must not travel through the conversation, so the operator types it into the page and the browser sends it straight to the server. A guest is admitted only through one of the location's access points, so a location without any turns every guest away. An access point already in another deployed location is refused: pause that one first.")
    .requireScopes(Auth.ContentWrite)
    // Needs the context, not just the arguments: the ticket is signed with the calling token's own
    // stored hash, so that revoking that token kills the pages it opened and issuing anybody else's
    // leaves them alone.
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
                    .slug}' no tiene un AP con la MAC $mac. Sin apMac, la página registra uno nuevo."))
        tokenId <- callerOf(ctx)
        row <- Tokens.byIdentifier(tokenId).someOrFail("El token que hizo la llamada ya no existe.")
        now <- Clock.instant
      yield ConfigureResponse(
        ticket = UiTicket.mint(row.tokenSha256, tokenId, current.slug, now),
        postUrl = s"$origin${UiRoutes.UnifiPath}",
        slug = current.slug,
        name = current.name,
        tokenSet = point.exists(_.tokenSet),
        host = point.map(_.host),
        port = point.flatMap(_.port),
        siteId = point.map(_.siteId),
        apMac = point.map(_.apMac),
        redirectUrl = point.flatMap(_.redirectUrl),
        durationMinutes = point.flatMap(_.durationMinutes)
      )
    .withUi(UnifiUi.AppUri)

  def status(): McpToolHandlerR[Queries.AccessPointsOf] =
    McpTool("get_unifi_config")
      .description(
        "A location's UniFi: each of its access points with its controller, without the tokens, and its access duration, redirect URL and switch. Worth checking before a deploy: a guest is admitted only through one of its access points, so a location without any turns every guest away.")
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
        "Change one of a location's access points without opening the form: its MAC, for an AP that was replaced; its controller's host, port and site id; and the access duration, redirect URL and UniFi switch of the guests who join through it. Each field given replaces the stored one; every field left out stays as it is. The token is set only through configure_unifi. A new MAC already in another deployed location is refused. Read when a guest arrives or is authorized, so it applies with no redeploy.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: UpdateAccessPointRequest) =>
        for
          slug   <- Refusals.slugOf(in.slug)
          mac    <- ZIO.fromEither(UiRoutes.parsedMac(in.apMac))
          newMac <- ZIO.foreach(in.newApMac)(raw => ZIO.fromEither(UiRoutes.parsedMac(raw)))
          _      <-
            ZIO.when(
              in.newApMac.isEmpty && in.host.isEmpty && in.port.isEmpty && in.siteId.isEmpty &&
                in.durationMinutes.isEmpty && in.redirectUrl.isEmpty && in.unifiEnabled.isEmpty)(
              ZIO.fail(
                "No hay nada que cambiar: indicá newApMac, host, port, siteId, durationMinutes, redirectUrl o unifiEnabled."))
          now <- Clock.instant
        yield UpdateAccessPointCommand(
          slug,
          mac,
          newMac,
          in.host,
          in.port,
          in.siteId,
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
        "Take an access point off a location, with its controller's settings and token. A guest who comes through it afterwards is turned away with an error; one already admitted keeps their access until it runs out, and its revoke is skipped. A deployed location's last access point is refused: without it every guest would be turned away, so pause the location first.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: RemoveAccessPointRequest) =>
        for
          slug <- Refusals.slugOf(in.slug)
          mac  <- ZIO.fromEither(UiRoutes.parsedMac(in.apMac))
          now  <- Clock.instant
        yield RemoveAccessPointCommand(slug, mac, now))((in, _: Unit) => stored(in.slug))

  private def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("No pude identificar el token que hizo la llamada.")

  private def statusOf(report: AccessPointsReport): StatusResponse = StatusResponse(
    slug = report.slug,
    configured = report.accessPoints.nonEmpty,
    accessPoints = report
      .accessPoints
      .map(point =>
        AccessPointView(
          point.apMac,
          point.host,
          point.port,
          point.siteId,
          point.tokenSet,
          point.durationMinutes,
          point.redirectUrl,
          point.enabled))
  )

  def all(origin: String): List[McpToolHandlerR[
    QuillSqlite & Queries.AccessPointsOf & OperatorContext & LocationFlows.UpdateAccessPoint &
      LocationFlows.RemoveAccessPoint]] = List(
    configure(origin),
    status(),
    updateAccessPoint(),
    removeAccessPoint())
end UnifiTools
