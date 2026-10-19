package whitelabel.captal.mcp.access

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}

import com.jamesward.ziohttp.mcp.auth.OauthScope
import io.getquill.*
import whitelabel.captal.mcp.{QuillArrow, Writes}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

import QuillArrow.->

/** Operator tokens, in the database and issued by a tool.
  *
  * Delegation attenuates: a token can only grant scopes it already holds, so the set of things a
  * token can reach never grows by being passed along. The seeded admin is the only one nobody
  * granted.
  */
object Tokens:

  /** Fixed, so seeding is an update rather than an insert of a second admin: rotating
    * `MCP_ADMIN_TOKEN` replaces the credential in place and the id `created_by` points at does not
    * move.
    */
  private val AdminId = "admin"

  /** The table is `mcp_tokens`; without this Quill derives the name from the type. */
  inline given SchemaMeta[Row] = schemaMeta[Row]("mcp_tokens")

  final case class Row(
      id: String,
      tokenSha256: String,
      name: String,
      scopes: String,
      createdBy: Option[String],
      createdAt: String,
      revokedAt: Option[String])

  /** A token as anything but the verifier sees it: never the secret, never the hash. */
  final case class View(
      id: String,
      name: String,
      scopes: Set[OauthScope],
      createdBy: Option[String],
      createdAt: String,
      revoked: Boolean)

  private inline def tokens = quote(query[Row])

  private inline def byHash = quote: (hashParam: String) =>
    query[Row].filter(_.tokenSha256 == hashParam).take(1)

  private inline def byId = quote: (idParam: String) =>
    query[Row].filter(_.id == idParam)

  /** SHA-256 of the token, base64url without padding. Unsalted and with no work factor, which
    * [[mint]]'s 256 bits of entropy is what makes safe.
    */
  def hash(token: String): String = Base64
    .getUrlEncoder
    .withoutPadding
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)))

  /** A fresh 256-bit secret, base64url without padding. The plaintext exists here and in the
    * response that carries it, and nowhere else afterwards.
    */
  def mint: UIO[String] = ZIO.succeed:
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def render(scopes: Set[OauthScope]): String = scopes
    .map(_.toString)
    .toList
    .sorted
    .mkString(",")

  private def parse(scopes: String): Set[OauthScope] =
    scopes.split(",").map(_.trim).filter(_.nonEmpty).map(OauthScope.apply).toSet

  private[mcp] def viewOf(row: Row): View = View(
    id = row.id,
    name = row.name,
    scopes = parse(row.scopes),
    createdBy = row.createdBy,
    createdAt = row.createdAt,
    revoked = row.revokedAt.isDefined)

  /** What the verifier needs, and the only read on the request path. Looked up by hash; a revoked
    * row answers `None`.
    */
  def authenticate(presented: String): ZIO[QuillSqlite, Nothing, Option[Row]] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(byHash(lift(hash(presented)))).orDie.map(_.headOption.filter(_.revokedAt.isEmpty))

  /** Writes the admin row if it is missing and replaces it if the configured token changed. Answers
    * whether anything was written.
    */
  def seedAdmin(token: String, name: String): ZIO[QuillSqlite, String, Boolean] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      val digest = hash(token)
      val now = Instant.now.toString
      for
        existing <- run(byId(lift(AdminId))).orDie
        changed  <-
          existing.headOption match
            case Some(row) if row.tokenSha256 == digest && row.revokedAt.isEmpty =>
              ZIO.succeed(false)
            case Some(_) =>
              run(
                byId(lift(AdminId)).update(
                  _.tokenSha256 -> lift(digest),
                  _.name        -> lift(name),
                  _.scopes      -> lift(render(Auth.AllScopes)),
                  // Rotation un-revokes the admin row.
                  _.revokedAt -> lift(Option.empty[String])
                )).orDie.flatMap(Writes.expectOne(_, "Rotating the admin token")).as(true)
            case None =>
              run(
                tokens.insertValue(
                  lift(Row(AdminId, digest, name, render(Auth.AllScopes), None, now, None))))
                .orDie
                .flatMap(Writes.expectOne(_, "Seeding the admin token"))
                .as(true)
      yield changed
      end for

  def create(name: String, scopes: Set[OauthScope], createdBy: String)
      : ZIO[QuillSqlite, String, (String, View)] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    for
      secret <- mint
      row = Row(
        id = UUID.randomUUID.toString,
        tokenSha256 = hash(secret),
        name = name,
        scopes = render(scopes),
        createdBy = Some(createdBy),
        createdAt = Instant.now.toString,
        revokedAt = None
      )
      affected <- run(tokens.insertValue(lift(row))).orDie
      _        <- Writes.expectOne(affected, s"Creating token '$name'")
    yield (secret, viewOf(row))

  def list: ZIO[QuillSqlite, Nothing, List[View]] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    run(tokens).orDie.map(_.map(viewOf).sortBy(_.createdAt))

  def byIdentifier(id: String): ZIO[QuillSqlite, Nothing, Option[Row]] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(byId(lift(id))).orDie.map(_.headOption)

  /** Soft: sets `revoked_at` and keeps the row, which `created_by` points at from every token it
    * minted.
    */
  def revoke(id: String): ZIO[QuillSqlite, String, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    run(byId(lift(id)).update(_.revokedAt -> lift(Option(Instant.now.toString))))
      .orDie
      .flatMap(Writes.expectOne(_, s"Revoking token '$id'"))

  def isAdmin(row: Row): Boolean = row.id == AdminId
end Tokens
