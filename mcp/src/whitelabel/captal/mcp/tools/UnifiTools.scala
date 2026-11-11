package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.mcp.apps.{UiRoutes, UiTicket, UnifiUi}
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** UniFi credentials configured via page, not arguments. Token never reaches the model; operator
  * types it into the page, browser posts to [[UiRoutes]].
  */
object UnifiTools:

  final case class ConfigureRequest(
      @description("Which location to configure. Call list_locations for the slugs.")
      slug: String)

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
      @description("Whether a token is already stored. Blank on the form keeps it.")
      tokenSet: Boolean,
      host: Option[String],
      port: Option[Int],
      siteId: Option[String],
      apMac: Option[String],
      redirectUrl: Option[String],
      durationMinutes: Option[Int])

  final case class StatusRequest(
      @description("Which location to report on.")
      slug: String)

  /** Status without the token. Reports whether configured, not what the config is. */
  final case class StatusResponse(
      @description("The location this describes.")
      slug: String,
      @description(
        "False means the portal still runs but never grants access: host, site and token are what `UnifiAccess` needs, and it builds nothing without all three.")
      configured: Boolean,
      host: Option[String],
      @description("Defaults to 443 when unset.")
      port: Option[Int],
      siteId: Option[String],
      @description("The AP the captive portal dispatcher matches on.")
      apMac: Option[String],
      @description(
        "Where the portal dispatcher sends this location's guests instead of its portal, for a portal hosted elsewhere; unset, they go to the portal. Not used while the location is paused.")
      redirectUrl: Option[String],
      @description("Defaults to 1440 when unset.")
      durationMinutes: Option[Int],
      @description("Whether a token is stored. The value itself is never returned.")
      tokenSet: Boolean,
      @description(
        "False means the portal never calls the UniFi controller for this location, whatever is configured: guests finish the flow and stay at Ready. Set with set_unifi_enabled.")
      enabled: Boolean)

  final case class SetEnabledRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "False stops every call to the location's UniFi controller; true resumes them. The stored settings are kept either way.")
      enabled: Boolean)

  final case class UpdateSettingsRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description("The controller's host name or IP. Left as it is when omitted.")
      host: Option[String] = None,
      @description("The controller's port, 1 to 65535. Left as it is when omitted.")
      port: Option[Int] = None,
      @description("The UniFi site id. Left as it is when omitted.")
      siteId: Option[String] = None,
      @description(
        "The MAC of the AP the captive portal dispatcher matches on, in any spelling; stored lowercase with colons. An empty string removes it. Left as it is when omitted.")
      apMac: Option[String] = None,
      @description(
        "Where the portal dispatcher sends this location's guests instead of its portal, an http or https URL, for a portal hosted elsewhere. Not where a guest goes after authorizing. An empty string removes it, and guests go to the portal. Left as it is when omitted.")
      redirectUrl: Option[String] = None,
      @description("Minutes of access each guest is granted. Left as it is when omitted.")
      durationMinutes: Option[Int] = None)

  given Schema[ConfigureRequest] = DeriveSchema.gen
  given Schema[ConfigureResponse] = DeriveSchema.gen
  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[StatusResponse] = DeriveSchema.gen
  given Schema[SetEnabledRequest] = DeriveSchema.gen
  given Schema[UpdateSettingsRequest] = DeriveSchema.gen

  /** @param origin
    *   Where this server is reached, used to build an absolute `postUrl`. The CSP that lets the
    *   browser actually reach it is declared on the page rather than here — see
    *   [[EntityResources]]; the host reads it from the resource and ignores anything the tool says
    *   about it.
    */
  def configure(origin: String): McpToolHandlerR[QuillSqlite] = McpTool("configure_unifi")
    .description(
      "Open the form that stores a location's UniFi credentials. Takes no token on purpose: it must not travel through the conversation, so the operator types it into the page and the browser sends it straight to the server. Until a location is configured the portal completes its whole flow and then leaves every guest at Ready without network access.")
    .requireScopes(Auth.ContentWrite)
    // Needs the context, not just the arguments: the ticket is signed with the calling token's own
    // stored hash, so that revoking that token kills the pages it opened and issuing anybody else's
    // leaves them alone.
    .handleWithContext: (in: ConfigureRequest, ctx: McpToolContext) =>
      for
        current <- UiRoutes.currentFor(in.slug.trim)
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
        tokenSet = current.tokenSet,
        host = current.host,
        port = current.port,
        siteId = current.siteId,
        apMac = current.apMac,
        redirectUrl = current.redirectUrl,
        durationMinutes = current.durationMinutes
      )
    .withUi(UnifiUi.AppUri)

  def status(): McpToolHandlerR[QuillSqlite] = McpTool("get_unifi_config")
    .description(
      "What UniFi settings a location has, without the token, and whether its UniFi calls are enabled. Worth checking before a deploy: a location missing any of them serves its portal and then strands every guest at Ready.")
    .readOnly
    .handle: (in: StatusRequest) =>
      UiRoutes.currentFor(in.slug.trim).map(statusOf)

  def setEnabled(): McpToolHandlerR[QuillSqlite] = McpTool("set_unifi_enabled")
    .description(
      "Turn a location's UniFi calls off or back on. Off, the portal still runs its whole flow but never asks the controller to authorize or revoke a guest — for a location whose network grants access some other way. Takes effect on the next guest that finishes, with no redeploy. The stored settings are kept, so turning it back on needs nothing else.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetEnabledRequest) =>
      UiRoutes.setEnabled(in.slug.trim, in.enabled).map(statusOf)

  def updateSettings(): McpToolHandlerR[QuillSqlite] = McpTool("update_unifi_settings")
    .description(
      "Change a location's UniFi settings that are not secret — host, port, site id, AP MAC, redirect URL, access duration — without opening the form. Each field given replaces the stored one; every field left out stays as it is. The API token is set only through configure_unifi. A location's portal reads these settings when its task starts, so they apply once the location is redeployed with deploy_location.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: UpdateSettingsRequest) =>
      for
        current <- UiRoutes.currentFor(in.slug.trim)
        changed <- ZIO.fromEither(applied(in, current))
        stored  <- UiRoutes.storeSettings(changed)
      yield statusOf(stored)

  /** `current` with the fields `in` gives, each checked as the portal will read it. */
  private def applied(
      in: UpdateSettingsRequest,
      current: UiRoutes.UnifiSettings): Either[String, UiRoutes.UnifiSettings] =
    def required(sent: Option[String], kept: Option[String], name: String) =
      sent.map(_.trim) match
        case None =>
          Right(kept)
        case Some("") =>
          Left(s"$name no puede quedar vacío: el portal lo necesita para llegar al controlador.")
        case Some(value) =>
          Right(Some(value))
    def between(sent: Option[Int], kept: Option[Int], name: String, max: Int) =
      sent match
        case None =>
          Right(kept)
        case Some(value) if value >= 1 && value <= max =>
          Right(Some(value))
        case Some(value) =>
          Left(s"$name debe estar entre 1 y $max, no $value.")
    // An empty string removes the value; anything else has to read as the portal will read it.
    def removable(sent: Option[String], kept: Option[String])(
        read: String => Either[String, String]) =
      sent.map(_.trim) match
        case None =>
          Right(kept)
        case Some("") =>
          Right(None)
        case Some(value) =>
          read(value).map(Some(_))
    val nothingGiven =
      in.host.isEmpty && in.port.isEmpty && in.siteId.isEmpty && in.apMac.isEmpty &&
        in.redirectUrl.isEmpty && in.durationMinutes.isEmpty
    for
      _ <- Either.cond(
        !nothingGiven,
        (),
        "No hay nada que cambiar: indicá al menos host, port, siteId, apMac, redirectUrl o durationMinutes.")
      host     <- required(in.host, current.host, "host")
      siteId   <- required(in.siteId, current.siteId, "siteId")
      port     <- between(in.port, current.port, "port", 65535)
      duration <- between(
        in.durationMinutes,
        current.durationMinutes,
        "durationMinutes",
        Int.MaxValue)
      apMac <-
        removable(in.apMac, current.apMac)(raw =>
          Mac
            .parse(raw)
            .map(_.show)
            .toRight(
              s"'$raw' no es una MAC: seis pares de dígitos hexadecimales, separados por ':' o '-' o juntos."))
      redirectUrl <-
        removable(in.redirectUrl, current.redirectUrl)(url =>
          Either.cond(
            url.startsWith("https://") || url.startsWith("http://"),
            url,
            s"'$url' no es una URL http o https."))
    yield current.copy(
      host = host,
      port = port,
      siteId = siteId,
      apMac = apMac,
      redirectUrl = redirectUrl,
      durationMinutes = duration)
    end for
  end applied

  private def statusOf(current: UiRoutes.UnifiSettings): StatusResponse = StatusResponse(
    slug = current.slug,
    configured = current.host.isDefined && current.siteId.isDefined && current.tokenSet,
    host = current.host,
    port = current.port,
    siteId = current.siteId,
    apMac = current.apMac,
    redirectUrl = current.redirectUrl,
    durationMinutes = current.durationMinutes,
    tokenSet = current.tokenSet,
    enabled = current.enabled
  )

  def all(origin: String): List[McpToolHandlerR[QuillSqlite]] = List(
    configure(origin),
    status(),
    setEnabled(),
    updateSettings())
end UnifiTools
