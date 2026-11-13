package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.mcp.apps.{UiRoutes, UiTicket, UnifiUi}
import whitelabel.captal.mcp.aws.AssetStore
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** A location's UniFi: the controller of each of its access points, configured through a page so
  * that no token reaches the model — the operator types it in and the browser posts it to
  * [[UiRoutes]] — and the location's own access duration, redirect URL and switch.
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
      tokenSet: Boolean)

  /** Status without the tokens. Reports whether each access point is configured, not its token. */
  final case class StatusResponse(
      @description("The location this describes.")
      slug: String,
      @description(
        "False means it has no access point, so every guest is turned away with an error: the AP a guest comes through is what says which controller grants them access. Register one with configure_unifi.")
      configured: Boolean,
      @description(
        "Its access points, each with its own controller. A guest is admitted only through one of these.")
      accessPoints: List[AccessPointView],
      @description(
        "Where the portal dispatcher sends this location's guests instead of its portal, for a portal hosted elsewhere; unset, they go to the portal. Not used while the location is paused.")
      redirectUrl: Option[String],
      @description("Minutes of access each guest is granted; 1440 when unset.")
      durationMinutes: Option[Int],
      @description(
        "False means the portal never calls a UniFi controller for this location, whatever is configured: guests finish the flow and stay at Ready. Set with set_unifi_enabled.")
      enabled: Boolean)

  final case class SetEnabledRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "False stops every call to the location's UniFi controllers; true resumes them. The stored settings are kept either way.")
      enabled: Boolean)

  final case class UpdateSettingsRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "Where the portal dispatcher sends this location's guests instead of its portal, an http or https URL, for a portal hosted elsewhere. Not where a guest goes after authorizing. An empty string removes it, and guests go to the portal. Left as it is when omitted.")
      redirectUrl: Option[String] = None,
      @description("Minutes of access each guest is granted. Left as it is when omitted.")
      durationMinutes: Option[Int] = None)

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
      siteId: Option[String] = None)

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
  given Schema[SetEnabledRequest] = DeriveSchema.gen
  given Schema[UpdateSettingsRequest] = DeriveSchema.gen
  given Schema[UpdateAccessPointRequest] = DeriveSchema.gen
  given Schema[RemoveAccessPointRequest] = DeriveSchema.gen

  /** @param origin
    *   Where this server is reached, used to build an absolute `postUrl`. The CSP that lets the
    *   browser actually reach it is declared on the page rather than here — see
    *   [[EntityResources]]; the host reads it from the resource and ignores anything the tool says
    *   about it.
    */
  def configure(origin: String): McpToolHandlerR[QuillSqlite] = McpTool("configure_unifi")
    .description(
      "Open the form that registers one of a location's access points with the UniFi controller that grants access through it — host, port, site id and API token — or changes one, along with the location's access duration and redirect URL. Takes no token on purpose: it must not travel through the conversation, so the operator types it into the page and the browser sends it straight to the server. A guest is admitted only through one of the location's access points, so a location without any turns every guest away. An access point already in another running location is refused: pause that one first.")
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
        tokenId <- ZIO
          .fromOption(Auth.callerId(ctx.principal))
          .orElseFail("No pude identificar el token que hizo la llamada.")
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
        redirectUrl = current.redirectUrl,
        durationMinutes = current.durationMinutes
      )
    .withUi(UnifiUi.AppUri)

  def status(): McpToolHandlerR[QuillSqlite] = McpTool("get_unifi_config")
    .description(
      "A location's UniFi: each of its access points with its controller, without the tokens, and the location's access duration, redirect URL and switch. Worth checking before a deploy: a guest is admitted only through one of its access points, so a location without any turns every guest away.")
    .readOnly
    .handle: (in: StatusRequest) =>
      UiRoutes.currentFor(in.slug.trim).map(statusOf)

  def setEnabled(): McpToolHandlerR[QuillSqlite] = McpTool("set_unifi_enabled")
    .description(
      "Turn a location's UniFi calls off or back on. Off, the portal still runs its whole flow but never asks a controller to authorize or revoke a guest — for a location whose network grants access some other way. Takes effect on the next guest that finishes, with no redeploy. The stored settings are kept, so turning it back on needs nothing else.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetEnabledRequest) =>
      UiRoutes.setEnabled(in.slug.trim, in.enabled).map(statusOf)

  def updateSettings(): McpToolHandlerR[QuillSqlite] = McpTool("update_unifi_settings")
    .description(
      "Change a location's own UniFi settings — the access duration each guest is granted and the redirect URL — without opening the form. Each field given replaces the stored one; every field left out stays as it is. An access point's settings are changed with update_access_point. Read when they are needed, so they apply with no redeploy.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: UpdateSettingsRequest) =>
      for
        current <- UiRoutes.currentFor(in.slug.trim)
        _       <-
          ZIO.when(in.redirectUrl.isEmpty && in.durationMinutes.isEmpty)(
            ZIO.fail("No hay nada que cambiar: indicá redirectUrl o durationMinutes."))
        redirectUrl <- ZIO.fromEither(
          removable(in.redirectUrl, current.redirectUrl)(url =>
            Either.cond(
              url.startsWith("https://") || url.startsWith("http://"),
              url,
              s"'$url' no es una URL http o https.")))
        duration <- ZIO.fromEither(
          between(in.durationMinutes, current.durationMinutes, "durationMinutes", Int.MaxValue))
        stored <- UiRoutes.storeLocationSettings(current.slug, redirectUrl, duration)
      yield statusOf(stored)

  def updateAccessPoint(): McpToolHandlerR[QuillSqlite & AssetStore] = McpTool(
    "update_access_point")
    .description(
      "Change one of a location's access points without opening the form: its MAC, for an AP that was replaced, and its controller's host, port and site id. Each field given replaces the stored one; every field left out stays as it is. The token is set only through configure_unifi. A new MAC already in another running location is refused. Read when a guest is authorized, so it applies with no redeploy.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: UpdateAccessPointRequest) =>
      for
        current <- UiRoutes.currentFor(in.slug.trim)
        mac     <- ZIO.fromEither(UiRoutes.macOf(in.apMac))
        point   <- ZIO
          .fromOption(current.accessPoints.find(_.apMac == mac))
          .orElseFail(
            s"'${current.slug}' no tiene un AP con la MAC $mac. get_unifi_config los lista.")
        _ <-
          ZIO.when(in.newApMac.isEmpty && in.host.isEmpty && in.port.isEmpty && in.siteId.isEmpty)(
            ZIO.fail("No hay nada que cambiar: indicá newApMac, host, port o siteId."))
        newMac <- ZIO.foreach(in.newApMac)(raw => ZIO.fromEither(UiRoutes.macOf(raw)))
        host   <- ZIO.fromEither(required(in.host, point.host, "host"))
        siteId <- ZIO.fromEither(required(in.siteId, point.siteId, "siteId"))
        port   <- ZIO.fromEither(between(in.port, point.port, "port", 65535))
        stored <- UiRoutes.storeAccessPoint(
          current.slug,
          mac,
          point.copy(apMac = newMac.getOrElse(mac), host = host, port = port, siteId = siteId))
      yield statusOf(stored)

  def removeAccessPoint(): McpToolHandlerR[QuillSqlite] = McpTool("remove_access_point")
    .description(
      "Take an access point off a location, with its controller's settings and token. A guest who comes through it afterwards is turned away with an error; one already admitted keeps their access until it runs out, and its revoke is skipped.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: RemoveAccessPointRequest) =>
      for
        mac    <- ZIO.fromEither(UiRoutes.macOf(in.apMac))
        stored <- UiRoutes.removeAccessPoint(in.slug.trim, mac)
      yield statusOf(stored)

  /** `sent` trimmed, or `kept` when it is not given; never blank. */
  private def required(sent: Option[String], kept: String, name: String): Either[String, String] =
    sent.map(_.trim) match
      case None =>
        Right(kept)
      case Some("") =>
        Left(s"$name no puede quedar vacío: el portal lo necesita para llegar al controlador.")
      case Some(value) =>
        Right(value)

  /** `sent` when it is between 1 and `max`, or `kept` when it is not given. */
  private def between(
      sent: Option[Int],
      kept: Option[Int],
      name: String,
      max: Int): Either[String, Option[Int]] =
    sent match
      case None =>
        Right(kept)
      case Some(value) if value >= 1 && value <= max =>
        Right(Some(value))
      case Some(value) =>
        Left(s"$name debe estar entre 1 y $max, no $value.")

  /** An empty string removes the value; anything else has to read as the portal will read it. */
  private def removable(sent: Option[String], kept: Option[String])(
      read: String => Either[String, String]): Either[String, Option[String]] =
    sent.map(_.trim) match
      case None =>
        Right(kept)
      case Some("") =>
        Right(None)
      case Some(value) =>
        read(value).map(Some(_))

  private def statusOf(current: UiRoutes.UnifiSettings): StatusResponse = StatusResponse(
    slug = current.slug,
    configured = current.accessPoints.nonEmpty,
    accessPoints = current
      .accessPoints
      .map(point =>
        AccessPointView(point.apMac, point.host, point.port, point.siteId, point.tokenSet)),
    redirectUrl = current.redirectUrl,
    durationMinutes = current.durationMinutes,
    enabled = current.enabled
  )

  def all(origin: String): List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(
    configure(origin),
    status(),
    setEnabled(),
    updateSettings(),
    updateAccessPoint(),
    removeAccessPoint())
end UnifiTools
