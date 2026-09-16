package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.auth.*
import zio.*
import zio.json.ast.Json

/** Bearer-token authentication for the backoffice.
  *
  * Static tokens rather than OAuth: the SDK supports JWKS and introspection, but neither is worth
  * standing up an identity provider for a handful of operators. The cost is real and worth stating
  * — these tokens do not expire, and rotating one means editing every `.mcp.json` that carries it.
  * Moving to OAuth later replaces [[verifier]] and nothing else; scopes and tools do not change.
  */
object Auth:
  /** The issuer advertised in the RFC 9728 metadata. Meaningless for static tokens, since there is
    * no authorization server to send a client to, but the field is required and the metadata is
    * served either way.
    */
  private val Issuer = "https://captal.invalid/static-tokens"

  /** Reads need only a valid token; writes are split so a translator cannot reshape surveys and
    * infrastructure stays out of reach of both until Part 3 adds it.
    */
  val I18nWrite: OauthScope = OauthScope("i18n:write")
  val ContentWrite: OauthScope = OauthScope("content:write")

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

  final case class Operator(name: String, scopes: Set[OauthScope])

  /** `MCP_TOKENS`, as `secret|name|scope,scope;secret|name|scope`.
    *
    * Parsed strictly: a malformed entry fails startup rather than being skipped, because a silently
    * dropped operator looks exactly like a revoked one.
    */
  def parse(spec: String): Either[String, List[(String, Operator)]] =
    val entries = spec.split(";").map(_.trim).filter(_.nonEmpty).toList
    if entries.isEmpty then
      Left("MCP_TOKENS is empty. Set at least one entry as secret|name|scope,scope.")
    else
      val parsed = entries.map: entry =>
        // -1 keeps a trailing empty field: "secret|name|" is an operator with no scopes, and the
        // default split drops it, silently turning a valid entry into a malformed two-field one.
        entry.split("\\|", -1).map(_.trim).toList match
          case secret :: name :: scopes :: Nil if secret.nonEmpty && name.nonEmpty =>
            Right(
              secret ->
                Operator(
                  name,
                  scopes.split(",").map(_.trim).filter(_.nonEmpty).map(OauthScope.apply).toSet))
          case _ =>
            Left(s"Malformed MCP_TOKENS entry '$entry'. Expected secret|name|scope,scope.")
      parsed.partitionMap(identity) match
        case (Nil, ok) =>
          val duplicates = ok.groupBy(_._1).filter(_._2.size > 1).keys
          if duplicates.nonEmpty then
            Left("MCP_TOKENS reuses the same secret for more than one operator.")
          else
            Right(ok)
        case (errors, _) =>
          Left(errors.mkString("; "))
    end if
  end parse

  /** Compares in constant time and over every entry.
    *
    * A `Map` lookup would short-circuit on the first differing byte of the hash bucket, and
    * returning early on a match would leak how far a guess got. Neither matters much against a
    * remote attacker, but a token is a credential and the linear scan costs nothing at this size.
    */
  private def lookup(operators: List[(String, Operator)], presented: String): Option[Operator] =
    val presentedBytes = presented.getBytes("UTF-8")
    operators.foldLeft(Option.empty[Operator]): (found, entry) =>
      val (secret, operator) = entry
      if java.security.MessageDigest.isEqual(secret.getBytes("UTF-8"), presentedBytes) then
        found.orElse(Some(operator))
      else
        found

  def verifier(operators: List[(String, Operator)], audience: ResourceUri): TokenVerifier[Any] =
    TokenVerifier.fromFunction: raw =>
      lookup(operators, raw) match
        case Some(operator) =>
          ZIO.succeed(
            Principal(
              subject = Some(operator.name),
              clientId = None,
              scopes = operator.scopes,
              audience = Set(audience.toString),
              issuer = None,
              expiresAt = None,
              raw = raw,
              claims = Json
                .Obj()
            ))
        case None =>
          // Deliberately says nothing about which part failed.
          ZIO.fail(AuthError.Invalid("Unknown token."))

  def mcpAuth(operators: List[(String, Operator)], audience: ResourceUri): McpAuth[Any] = McpAuth(
    AuthorizationServer(Issuer),
    verifier(operators, audience)).withResourceUri(audience)
end Auth
