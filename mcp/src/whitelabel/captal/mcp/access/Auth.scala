package whitelabel.captal.mcp.access

import com.jamesward.ziohttp.mcp.auth.*
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.json.ast.Json

/** Bearer-token authentication for the backoffice.
  *
  * Static tokens that never expire, stored hashed in [[Tokens]] and issued by [[TokenTools]].
  */
object Auth:
  /** The issuer advertised in the RFC 9728 metadata: this server's own origin.
    *
    * It must resolve — a client that reads the metadata dereferences it on connect.
    */
  private def issuerFor(resource: ResourceUri): String =
    val parsed = java.net.URI.create(resource.toString)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

  /** Writing translations. */
  val I18nWrite: OauthScope = OauthScope("i18n:write")

  /** Writing a location's own content: the location row, advertisers, videos, their surveys. */
  val ContentWrite: OauthScope = OauthScope("content:write")

  /** Writing the deployment-wide identification surveys: email, profiling, location. */
  val IdentityWrite: OauthScope = OauthScope("identity:write")

  /** Issuing tokens. Delegable: a token can only grant scopes it already holds. */
  val TokensWrite: OauthScope = OauthScope("tokens:write")

  /** Standing a location's portal up in AWS, and pausing or resuming it. */
  val InfraDeploy: OauthScope = OauthScope("infra:deploy")

  /** Tearing a location's infrastructure down, assets under its slug prefix included. */
  val InfraDestroy: OauthScope = OauthScope("infra:destroy")

  /** The resource this server declares itself to be, and the audience minted into every principal.
    *
    * Read from `MCP_RESOURCE_URI`, defaulting to `http://localhost:8081/mcp`. The middleware
    * enforces RFC 8707 audience binding: a principal whose audience does not match this is rejected
    * before scopes are considered.
    */
  def resourceUri: ZIO[Any, Throwable, ResourceUri] = System
    .env("MCP_RESOURCE_URI")
    .orDie
    .map(_.getOrElse("http://localhost:8081/mcp"))
    .flatMap: raw =>
      ZIO
        .fromEither(ResourceUri.parse(raw))
        .mapError(e => new IllegalStateException(s"MCP_RESOURCE_URI is not usable: $e"))

  /** Every scope there is: what the seeded admin holds, and the ceiling on any grant. */
  val AllScopes: Set[OauthScope] = Set(
    I18nWrite,
    ContentWrite,
    IdentityWrite,
    TokensWrite,
    InfraDeploy,
    InfraDestroy)

  /** Verifies a presented token against the tokens table.
    *
    * `subject` is the token's id — what `created_by` records, what `revoke_token` addresses and
    * what [[UiTicket]] keys a page's write on. The holder's name travels in the `name` claim.
    */
  def verifier(audience: ResourceUri): TokenVerifier[QuillSqlite] = TokenVerifier.fromFunction:
    raw =>
      Tokens
        .authenticate(raw)
        .flatMap:
          case Some(row) =>
            ZIO.succeed(
              Principal(
                subject = Some(row.id),
                clientId = None,
                scopes = Tokens.viewOf(row).scopes,
                audience = Set(audience.toString),
                issuer = None,
                expiresAt = None,
                raw = raw,
                claims = Json.Obj("name" -> Json.Str(row.name))
              ))
          case None =>
            // Unknown, revoked and malformed get one answer.
            ZIO.fail(AuthError.Invalid("Unknown token."))

  /** The id of the token behind a call, for the tools that need to know who is asking rather than
    * only whether they may.
    */
  def callerId(principal: Option[Principal]): Option[String] = principal.flatMap(_.subject)

  /** The RFC 9728 metadata, with `scopes_supported` filled in so the document names what the
    * middleware enforces.
    *
    * @param audience
    *   what this server declares itself to be, and what every minted principal is bound to.
    */
  def mcpAuth(audience: ResourceUri): McpAuth[QuillSqlite] = McpAuth(
    AuthorizationServer(issuerFor(audience)),
    verifier(audience))
    .withResourceUri(audience)
    .copy(
      scopesSupported = Chunk.fromIterable(AllScopes.toList.sortBy(_.toString)),
      resourceName = Some("Captal backoffice"))
end Auth
