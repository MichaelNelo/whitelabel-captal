package whitelabel.captal.mcp.access

import zio.http.*
import zio.json.ast.Json

/** The authorization server metadata this server has to publish, saying honestly that there is no
  * authorization server.
  *
  * RFC 9728 lets a protected resource name the issuers whose tokens it accepts, and an issuer
  * identifier is not a label — by RFC 8414 it is the base URL its metadata is fetched from. So
  * whatever we name, some client eventually dereferences it. That is how an unresolvable
  * `captal.invalid` cost 10 seconds per DNS lookup, pushed Claude Desktop's handshake past its
  * timeout, and cost the host its resource cache.
  *
  * Naming ourselves fixed the delay and left a smaller untruth: we claimed to be an authorization
  * server and answered 404. This is the document that makes it true.
  *
  * `grant_types_supported: []` is the whole point. Tokens for this resource exist and none of them
  * are obtainable through an OAuth flow — they are issued out of band by `create_token` and stored
  * hashed. With no grants declared, RFC 8414 does not require an authorization or token endpoint,
  * so omitting both is consistent rather than missing.
  *
  * And it beats the 404 it replaces: a 404 says "nothing here, perhaps try again", while this says
  * "there is no flow, stop looking" and names the scopes that do exist.
  */
object OauthMetadata:

  /** Fetched before any token is presented, so it is served unauthenticated — and carries nothing
    * that is not already public: the scope names and this server's own address.
    */
  private[mcp] def document(issuer: String): Json = Json.Obj(
    "issuer" -> Json.Str(issuer),
    // Deliberately empty, and the most informative field here.
    "grant_types_supported"    -> Json.Arr(),
    "response_types_supported" -> Json.Arr(),
    "scopes_supported"         ->
      Json.Arr(
        zio.Chunk.fromIterable(Auth.AllScopes.map(_.toString).toList.sorted.map(Json.Str(_))))
  )

  /** At the bare path because our issuer is the bare origin. RFC 8414 appends the issuer's path
    * component when it has one; ours has none.
    */
  def routes(issuer: String): Routes[Any, Response] =
    val body = document(issuer).toString
    Routes(
      Method.GET / ".well-known" / "oauth-authorization-server" ->
        handler(
          Response
            .json(body)
            // Static for the life of the deployment, and fetched on every client connect.
            .addHeader(Header.CacheControl.MaxAge(3600))))
end OauthMetadata
