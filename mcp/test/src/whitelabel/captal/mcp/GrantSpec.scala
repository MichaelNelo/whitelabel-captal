package whitelabel.captal.mcp

import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.tools.Grant
import zio.test.*

/** The grant-to-scope mapping, pinned because it failed silently.
  *
  * `scopeOf` matched on unqualified lowercase names, which in a pattern are fresh variables rather
  * than the enum cases they read like. The first case therefore matched everything and every grant
  * mapped to `i18n:write`: `create_token` issued tokens carrying a scope nobody asked for while
  * withholding the ones they did, and the attenuation check compared the wrong set. Nothing failed
  * — the tokens were real, they simply opened the wrong doors.
  *
  * A compiler warning caught it and a test did not, which is the reason for this file.
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
      // The property behind the four assertions above: distinct grants cannot collapse onto one
      // scope. Written separately because a fifth grant added later is covered by this and not by
      // an enumeration someone has to remember to extend.
      test("no two grants share a scope"):
        val scopes = Grant.values.toList.map(Grant.scopeOf)
        assertTrue(scopes.distinct.size == Grant.values.length)
      ,
      // The ceiling the admin holds has to contain everything a grant can name, or a scope would
      // exist that no token could ever be given.
      test("every grant names a scope the admin holds"):
        assertTrue(Grant.values.toList.map(Grant.scopeOf).forall(Auth.AllScopes.contains))
    )
end GrantSpec
