package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.auth.{AuthError, OauthScope, ResourceUri}
import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.platform.RqliteDataSource
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.test.*

import Tokens.given

/** Authentication against the tokens table.
  *
  * What these pin is the part that fails open if it is wrong: that the secret is never stored, that
  * a revoked token stops working without waiting for a reconnect, and that a principal carries the
  * token's id rather than a name — [[UiTicket]] keys a page's write on that id, so a principal that
  * carried something else would sign tickets nobody could verify.
  */
object AuthSpec extends ZIOSpecDefault:
  private val audience = ResourceUri.unsafe("https://mcp.test/mcp")

  private def clear: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    quill
      .run(query[Tokens.Row])
      .flatMap: owned =>
        ZIO.foreachDiscard(owned.filter(_.name.startsWith("spec-")).map(_.id)): id =>
          quill.run(query[Tokens.Row].filter(_.id == lift(id)).delete)

  private def issue(name: String, scopes: Set[OauthScope]) = Tokens
    .create(name, scopes, "admin")
    .orDieWith(new RuntimeException(_))

  def spec =
    suite("Auth against rqlite")(
      test("the token itself is never stored, only its hash"):
        for
          _      <- clear
          issued <- issue("spec-alice", Set(Auth.ContentWrite))
          (secret, view) = issued
          stored <- Tokens.byIdentifier(view.id)
        yield assertTrue(
          stored.isDefined,
          stored.get.tokenSha256 == Tokens.hash(secret),
          stored.get.tokenSha256 != secret)
      ,
      test("a known token yields a principal carrying its id and scopes"):
        for
          _      <- clear
          issued <- issue("spec-alice", Set(Auth.ContentWrite, Auth.I18nWrite))
          (secret, view) = issued
          principal <- Auth.verifier(audience).verify(secret)
        yield assertTrue(
          // The id, not the name: `create_token` records it as `created_by` and `UiTicket` keys on
          // it, so a principal carrying anything else would break both.
          principal.subject.contains(view.id),
          principal.hasScope(Auth.ContentWrite),
          principal.hasScope(Auth.I18nWrite),
          !principal.hasScope(Auth.TokensWrite)
        )
      ,
      test("an unknown token is rejected without saying why"):
        for
          _      <- clear
          result <- Auth.verifier(audience).verify("not-a-token").either
        yield assertTrue(
          result.isLeft,
          result.swap.toOption.get.isInstanceOf[AuthError.Invalid],
          !result.swap.toOption.get.toString.toLowerCase.contains("revoked"))
      ,
      // Revocation has to bite on the next call rather than the next connection, which is why the
      // verifier reads the row every time instead of caching a principal.
      test("a revoked token stops authenticating"):
        for
          _      <- clear
          issued <- issue("spec-beto", Set(Auth.ContentWrite))
          (secret, view) = issued
          before <- Auth.verifier(audience).verify(secret).either
          _      <- Tokens.revoke(view.id).orDieWith(new RuntimeException(_))
          after  <- Auth.verifier(audience).verify(secret).either
        yield assertTrue(before.isRight, after.isLeft)
      ,
      test("seeding the admin twice with the same value changes nothing"):
        for
          first  <- Tokens.seedAdmin("admin-secret", "admin").orDieWith(new RuntimeException(_))
          second <- Tokens.seedAdmin("admin-secret", "admin").orDieWith(new RuntimeException(_))
          row    <- Tokens.byIdentifier("admin")
        yield assertTrue(
          second == false,
          row.isDefined,
          row.get.tokenSha256 == Tokens.hash("admin-secret"))
      ,
      // Rotation is how a deployment replaces the credential, and it must not produce a second
      // admin: every token issued points at this id through `created_by`.
      test("seeding with a new value rotates the same row"):
        for
          _       <- Tokens.seedAdmin("admin-secret", "admin").orDieWith(new RuntimeException(_))
          rotated <- Tokens.seedAdmin("admin-rotated", "admin").orDieWith(new RuntimeException(_))
          row     <- Tokens.byIdentifier("admin")
          old     <- Auth.verifier(audience).verify("admin-secret").either
          fresh   <- Auth.verifier(audience).verify("admin-rotated").either
        yield assertTrue(
          rotated,
          row.get.tokenSha256 == Tokens.hash("admin-rotated"),
          old.isLeft,
          fresh.isRight)
      ,
      test("the admin holds every scope there is"):
        for
          _   <- Tokens.seedAdmin("admin-secret", "admin").orDieWith(new RuntimeException(_))
          row <- Tokens.byIdentifier("admin")
        yield assertTrue(Tokens.viewOf(row.get).scopes == Auth.AllScopes)
      // Both histories are laid down once by `mcp.test` before forking — see `migratedRqliteUrl` in
      // build.mill, which runs `Migrate` and then `McpMigrate` for the reasons this spec used to
      // carry in a comment here.
    ).provideShared(
      RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)) @@
      TestAspect.sequential
end AuthSpec
