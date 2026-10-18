package whitelabel.captal.mcp

import whitelabel.captal.mcp.access.{Auth, OauthMetadata}
import zio.test.*

/** What this server publishes about how its tokens are obtained.
  *
  * Pinned because the cost of getting it wrong is nothing like its size. The previous value of the
  * issuer was an unresolvable hostname, chosen on the reasoning that with no authorization server
  * the field was meaningless; a client dereferenced it, spent 10 seconds per DNS lookup, and the
  * handshake blew past Claude Desktop's timeout — which closed the transport and took the host's
  * resource cache with it. Nothing in this document is load-bearing for a request, and all of it is
  * load-bearing for a connection.
  */
object OauthMetadataSpec extends ZIOSpecDefault:

  private val issuer = "http://localhost:8081"

  def spec =
    suite("OauthMetadata")(
      // The one claim worth being exact about: tokens exist, no flow produces them. An empty list
      // says that; a missing field would let a client assume the RFC 8414 defaults, which are
      // authorization_code and implicit — two flows this server does not have.
      test("declares no grant types rather than omitting them"):
        val doc = OauthMetadata.document(issuer).toString
        assertTrue(
          doc.contains("\"grant_types_supported\":[]"),
          doc.contains("\"response_types_supported\":[]"))
      ,
      test("names itself as the issuer"):
        assertTrue(OauthMetadata.document(issuer).toString.contains(s"\"issuer\":\"$issuer\""))
      ,
      // The same ones the middleware enforces. A scope the server rejects calls for and never
      // publishes is a contract kept secret from the people who have to satisfy it.
      //
      // Spelled out rather than counted, which is what this was. A count fails with "6 was not
      // equal to 4" and names neither the scope that arrived nor whether publishing it was
      // intended; the set fails naming it. Adding a scope is meant to land here, because the half
      // that is easy to forget is `Grant.scopeOf` — a scope no grant maps to can be enforced but
      // never issued.
      test("publishes exactly the scopes that are enforced"):
        val doc = OauthMetadata.document(issuer).toString
        assertTrue(
          Auth.AllScopes.forall(scope => doc.contains(s"\"$scope\"")),
          Auth.AllScopes.map(_.toString) ==
            Set(
              "i18n:write",
              "content:write",
              "identity:write",
              "tokens:write",
              "infra:deploy",
              "infra:destroy")
        )
      ,
      // It is fetched before any token is presented, so it must not depend on one or leak one.
      test("carries nothing secret"):
        val doc = OauthMetadata.document(issuer).toString
        assertTrue(
          !doc.contains("token_endpoint"),
          !doc.contains("client_secret"),
          !doc.toLowerCase.contains("bearer"))
    )
end OauthMetadataSpec
