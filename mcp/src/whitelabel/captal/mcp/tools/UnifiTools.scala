package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
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
      @description("Where a guest is sent after authorizing.")
      redirectUrl: Option[String],
      @description("Defaults to 1440 when unset.")
      durationMinutes: Option[Int],
      @description("Whether a token is stored. The value itself is never returned.")
      tokenSet: Boolean)

  given Schema[ConfigureRequest] = DeriveSchema.gen
  given Schema[ConfigureResponse] = DeriveSchema.gen
  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[StatusResponse] = DeriveSchema.gen

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
      "What UniFi settings a location has, without the token. Worth checking before a deploy: a location missing any of them serves its portal and then strands every guest at Ready.")
    .readOnly
    .handle: (in: StatusRequest) =>
      UiRoutes
        .currentFor(in.slug.trim)
        .map: current =>
          StatusResponse(
            slug = current.slug,
            configured = current.host.isDefined && current.siteId.isDefined && current.tokenSet,
            host = current.host,
            port = current.port,
            siteId = current.siteId,
            apMac = current.apMac,
            redirectUrl = current.redirectUrl,
            durationMinutes = current.durationMinutes,
            tokenSet = current.tokenSet
          )

  def all(origin: String): List[McpToolHandlerR[QuillSqlite]] = List(configure(origin), status())
end UnifiTools
