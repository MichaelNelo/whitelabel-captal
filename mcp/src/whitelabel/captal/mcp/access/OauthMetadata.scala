package whitelabel.captal.mcp.access

import zio.http.*
import zio.json.ast.Json

/** The RFC 8414 authorization server metadata, saying that there is no authorization server.
  *
  * `grant_types_supported: []` is the substance: tokens for this resource exist and none of them
  * are obtainable through an OAuth flow — they are issued out of band by `create_token` and stored
  * hashed. With no grants declared, RFC 8414 requires neither an authorization nor a token
  * endpoint, so omitting both is consistent rather than missing.
  */
object OauthMetadata:

  /** Fetched before any token is presented, so it is served unauthenticated — and carries nothing
    * that is not already public: the scope names and this server's own address.
    */
  private[mcp] def document(issuer: String): Json = Json.Obj(
    "issuer" -> Json.Str(issuer),
    // Empty: no OAuth flow issues these tokens.
    "grant_types_supported"    -> Json.Arr(),
    "response_types_supported" -> Json.Arr(),
    "scopes_supported"         ->
      Json.Arr(
        zio.Chunk.fromIterable(Auth.AllScopes.map(_.toString).toList.sorted.map(Json.Str(_))))
  )

  /** Served at the bare `/.well-known/oauth-authorization-server`: RFC 8414 appends the issuer's
    * path component when it has one, and ours is the bare origin.
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
