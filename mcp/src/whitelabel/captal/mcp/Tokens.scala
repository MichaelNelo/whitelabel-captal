package whitelabel.captal.mcp

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}

import com.jamesward.ziohttp.mcp.auth.OauthScope
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

import QuillArrow.->

/** Operator tokens, in the database rather than in `MCP_TOKENS`.
  *
  * The environment variable could only be edited by whoever deploys, so handing a translator their
  * own credential meant a redeploy — and a credential that takes a redeploy to issue is one people
  * share instead. These are issued by a tool, which is the point.
  *
  * Delegation attenuates: a token can only grant scopes it already holds. That is the whole
  * authorization model here, and it means the set of things a token can reach never grows by being
  * passed along. The seeded admin is the only one nobody granted.
  */
object Tokens:

  /** Fixed, so seeding is an update rather than an insert of a second admin. Rotating
    * `MCP_ADMIN_TOKEN` and restarting therefore replaces the credential in place; the id other
    * tokens point at through `created_by` does not move.
    */
  private val AdminId = "admin"

  /** Without this Quill derives the table from the type and looks for `row`, which exists nowhere —
    * and rqlite answers a select against a missing table with "No result set returned", which reads
    * like a driver problem rather than a naming one.
    */
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

  /** SHA-256, unsalted and without a work factor, which is right here and would be wrong for a
    * password. [[mint]] generates 256 bits of entropy, so there is no dictionary to attack and
    * nothing a slow hash would buy — only a cost paid on every request.
    */
  def hash(token: String): String = Base64
    .getUrlEncoder
    .withoutPadding
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)))

  /** The plaintext exists here and in the response that carries it, and nowhere else afterwards. */
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

  /** What the verifier needs, and the only read on the request path.
    *
    * Looked up by hash, so the index does the work a constant-time scan used to: there is nothing
    * secret to compare here, because the hash of a wrong guess is simply absent.
    */
  def authenticate(presented: String): ZIO[QuillSqlite, Nothing, Option[Row]] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(byHash(lift(hash(presented)))).orDie.map(_.headOption.filter(_.revokedAt.isEmpty))

  /** Writes the admin row if it is missing and replaces it if the configured token changed.
    *
    * Seeded from the environment rather than generated, for two reasons. A generated token has to
    * be shown, and the only channel at startup is the log — which is CloudWatch, the last place a
    * credential with every scope belongs. And this database is known to lose data: with a seeded
    * admin, recovery is a restart that re-creates the same row and leaves every `.mcp.json` still
    * working; with a generated one, every loss would lock out every operator at once.
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
                  // Rotation un-revokes: the admin is how a deployment recovers, and leaving it
                  // revoked would make the recovery path the one thing that cannot be recovered.
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

  /** Soft, because `created_by` points at it from every token it minted. Deleting would orphan that
    * record exactly when someone is working out why those tokens exist.
    */
  def revoke(id: String): ZIO[QuillSqlite, String, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    run(byId(lift(id)).update(_.revokedAt -> lift(Option(Instant.now.toString))))
      .orDie
      .flatMap(Writes.expectOne(_, s"Revoking token '$id'"))

  def isAdmin(row: Row): Boolean = row.id == AdminId
end Tokens
