package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.auth.*
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*
import zio.json.ast.Json

/** Bearer-token authentication for the backoffice.
  *
  * Static tokens rather than OAuth: the SDK supports JWKS and introspection, but neither is worth
  * standing up an identity provider for a handful of operators. The cost is real and worth stating
  * — these tokens do not expire, so a leaked one is good until somebody revokes it. Moving to OAuth
  * later replaces [[verifier]] and nothing else; scopes and tools do not change.
  *
  * They live in [[Tokens]], hashed, and are issued by [[TokenTools]] rather than by a deployment.
  */
object Auth:
  /** The issuer advertised in the RFC 9728 metadata.
    *
    * This server's own origin, because a client that reads the metadata will try to resolve it.
    * That is the part the previous value got wrong: it was `https://captal.invalid/static-tokens`,
    * chosen on the reasoning that with static tokens there is no authorization server to send
    * anyone to, so the field was meaningless. The field was not meaningless — it was unreachable,
    * which is a different thing and much worse.
    *
    * `.invalid` is reserved precisely so that it never resolves, and a name that never resolves
    * does not fail fast: measured at 10,011 ms per lookup before the resolver gives up.
    * `mcp-remote` performs discovery on connect, so every Claude Desktop launch spent ~20s in DNS,
    * Desktop timed the connection out at ~21.7s and closed the transport, and closing it
    * invalidated the host's resource cache — which is why the model could see this server's tools
    * and never its resources. One unreachable hostname, four layers away from the symptom.
    *
    * Pointing at ourselves makes discovery fail in milliseconds with a 404 instead. There is still
    * no authorization server; the client now finds that out immediately.
    */
  private def issuerFor(resource: ResourceUri): String =
    val parsed = java.net.URI.create(resource.toString)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

  /** Reads need only a valid token; writes are split so a translator cannot reshape surveys and
    * infrastructure stays out of reach of all of them until Part 3 adds it.
    */
  val I18nWrite: OauthScope = OauthScope("i18n:write")
  val ContentWrite: OauthScope = OauthScope("content:write")

  /** Separate from [[ContentWrite]] because what it guards is not a location's content.
    *
    * The email, profiling and location surveys are deployment-wide: `SurveyRepositoryQuill` picks
    * them by category with no location filter anywhere, so there is exactly one of each for every
    * portal at once. An operator who looks after one cafe has no business replacing the question
    * every guest of every location answers — and with a single write scope, adding an option to
    * their own advertiser survey and rewriting the identification flow were the same permission.
    *
    * Declared rather than checked inside the tool so `tools/list` stays honest about what each one
    * needs, which is what Part 6 scopes a token against.
    */
  val IdentityWrite: OauthScope = OauthScope("identity:write")

  /** Issuing tokens, and delegable like any other scope: a token that holds it can grant it.
    *
    * That is deliberate rather than an oversight. Delegation here attenuates — a token can only
    * grant scopes it already holds — so passing this along never widens what the chain can reach,
    * only who may extend it. Making it the one indelegable scope would mean every new operator went
    * through whoever holds the admin, which is the bottleneck this replaced.
    */
  val TokensWrite: OauthScope = OauthScope("tokens:write")

  /** Standing a location's portal up, and pausing or resuming it.
    *
    * Separate from `content:write` because the blast radius is different in kind, not in degree: a
    * content token writes rows in a database this server owns, and this one creates ECS services,
    * target groups and listener rules in an account. A leaked content token costs a portal's copy;
    * a leaked deploy token costs infrastructure.
    *
    * `create_location` stays under `content:write` and that is consistent, not an exception — it
    * writes one row and nothing in AWS. The split only becomes load-bearing once a tool can act on
    * the account, which is here.
    */
  val InfraDeploy: OauthScope = OauthScope("infra:deploy")

  /** Tearing a location's infrastructure down, and deliberately not the same scope as deploying it.
    *
    * Destroying is the one operation that loses something: the assets under the slug's prefix go
    * with it, and nothing recreates them. Whoever deploys all day has no need of that, and a scope
    * is how `tools/list` can decline to mention it rather than refusing at the call.
    */
  val InfraDestroy: OauthScope = OauthScope("infra:destroy")

  /** The audience minted into every principal, and the resource this server declares itself to be.
    *
    * The middleware enforces RFC 8707 audience binding unconditionally: a principal whose audience
    * does not match the resource URI is rejected before scopes are even considered. With tokens
    * issued by an authorization server that binding is meaningful. With static tokens there is no
    * issuer to bind anything, so the server states which resource it is and mints a matching
    * audience — the check still rejects a token replayed against a different deployment, which is
    * the part worth keeping.
    */
  def resourceUri: ZIO[Any, Throwable, ResourceUri] = System
    .env("MCP_RESOURCE_URI")
    .orDie
    .map(_.getOrElse("http://localhost:8081/mcp"))
    .flatMap: raw =>
      ZIO
        .fromEither(ResourceUri.parse(raw))
        .mapError(e => new IllegalStateException(s"MCP_RESOURCE_URI is not usable: $e"))

  /** Every scope there is, which is what the seeded admin holds and the ceiling on what any token
    * can be granted. Listed rather than derived so adding a scope is a decision about who gets it
    * rather than something the admin silently acquires.
    */
  val AllScopes: Set[OauthScope] = Set(
    I18nWrite,
    ContentWrite,
    IdentityWrite,
    TokensWrite,
    InfraDeploy,
    InfraDestroy)

  /** Verifies against the tokens table.
    *
    * `subject` carries the token's id rather than its holder's name: it is what `created_by`
    * records, what `revoke_token` addresses, and what [[UiTicket]] keys a page's write on. The name
    * is for reading, and rides along in the claims.
    *
    * The environment no longer supplies a list. One list in a variable only whoever deploys can
    * edit, and another in a table operators write to, would be two answers to "who may write" and
    * they would drift.
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
            // Deliberately says nothing about which part failed: unknown, revoked and malformed
            // are one answer.
            ZIO.fail(AuthError.Invalid("Unknown token."))

  /** The token behind a call, for the tools that need to know who is asking rather than only
    * whether they may. [[TokenTools]] attenuates against it and [[UnifiTools]] keys a ticket on it.
    */
  def callerId(principal: Option[Principal]): Option[String] = principal.flatMap(_.subject)

  /** @param audience
    *   what this server declares itself to be, and what every minted principal is bound to.
    *
    * The metadata is filled in rather than left at its defaults. `scopes_supported` was empty, so
    * the document omitted it and published no hint that these four scopes exist — while the
    * middleware rejected calls for lacking them. Saying out loud what is enforced costs nothing and
    * is the only part of this document that was ours to get right: `authorization_servers` is
    * optional in RFC 9728 and the honest value is no value, which `NonEmptyChunk` in the SDK's
    * `McpAuth` makes unexpressible.
    */
  def mcpAuth(audience: ResourceUri): McpAuth[QuillSqlite] = McpAuth(
    AuthorizationServer(issuerFor(audience)),
    verifier(audience))
    .withResourceUri(audience)
    .copy(
      scopesSupported = Chunk.fromIterable(AllScopes.toList.sortBy(_.toString)),
      resourceName = Some("Captal backoffice"))
end Auth
