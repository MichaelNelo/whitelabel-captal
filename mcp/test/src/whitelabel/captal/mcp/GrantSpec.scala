package whitelabel.captal.mcp

import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.tools.Grant
import zio.test.*

/** The grant-to-scope mapping: every grant maps to its own scope, and only to a scope the admin
  * holds.
  */
object GrantSpec extends ZIOSpecDefault:

  def spec =
    suite("Grant")(
      test("every grant maps to its own scope"):
        assertTrue(
          Grant.scopeOf(Grant.i18n_write) == Auth.I18nWrite,
          Grant.scopeOf(Grant.content_write) == Auth.ContentWrite,
          Grant.scopeOf(Grant.identity_write) == Auth.IdentityWrite,
          Grant.scopeOf(Grant.tokens_write) == Auth.TokensWrite
        )
      ,
      // The property behind the four assertions above, so a grant added later is covered too.
      test("no two grants share a scope"):
        val scopes = Grant.values.toList.map(Grant.scopeOf)
        assertTrue(scopes.distinct.size == Grant.values.length)
      ,
      test("every grant names a scope the admin holds"):
        assertTrue(Grant.values.toList.map(Grant.scopeOf).forall(Auth.AllScopes.contains))
    )
end GrantSpec
