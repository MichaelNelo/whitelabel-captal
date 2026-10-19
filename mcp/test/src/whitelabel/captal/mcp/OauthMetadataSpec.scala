package whitelabel.captal.mcp

import whitelabel.captal.mcp.access.{Auth, OauthMetadata}
import zio.test.*

/** What this server publishes about how its tokens are obtained. */
object OauthMetadataSpec extends ZIOSpecDefault:

  private val issuer = "http://localhost:8081"

  def spec =
    suite("OauthMetadata")(
      // An empty list says tokens exist and no flow produces them; a missing field would let a
      // client assume the RFC 8414 defaults of authorization_code and implicit.
      test("declares no grant types rather than omitting them"):
        val doc = OauthMetadata.document(issuer).toString
        assertTrue(
          doc.contains("\"grant_types_supported\":[]"),
          doc.contains("\"response_types_supported\":[]"))
      ,
      test("names itself as the issuer"):
        assertTrue(OauthMetadata.document(issuer).toString.contains(s"\"issuer\":\"$issuer\""))
      ,
      // The same ones the middleware enforces, spelled out so that adding a scope lands here.
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
