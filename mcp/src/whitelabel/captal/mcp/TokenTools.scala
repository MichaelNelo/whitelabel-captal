package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.auth.OauthScope
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** The scopes a token can be granted, as an enum so the schema refuses anything else.
  *
  * Underscores because a colon is not a legal identifier and the alternative was free text. The
  * codebase already made this trade for `QuestionType`: a schema that lies about what it accepts is
  * worse than an ugly name.
  */
enum Grant:
  case i18n_write
  case content_write
  case identity_write
  case tokens_write
  case infra_deploy
  case infra_destroy

object Grant:
  given Schema[Grant] = DeriveSchema.gen

  /** Qualified on purpose.
    *
    * An unqualified lowercase name in a pattern is a fresh variable, not the enum case it reads
    * like — so `case i18n_write =>` matched every grant and this returned `i18n:write` for all
    * four. `create_token` would have issued tokens holding a scope nobody asked for and silently
    * withholding the ones they did, and the attenuation check would have compared the wrong set.
    * The names are lowercase because the schema publishes them; the prefix is what makes them
    * patterns rather than bindings.
    */
  def scopeOf(grant: Grant): OauthScope =
    grant match
      case Grant.i18n_write =>
        Auth.I18nWrite
      case Grant.content_write =>
        Auth.ContentWrite
      case Grant.identity_write =>
        Auth.IdentityWrite
      case Grant.tokens_write =>
        Auth.TokensWrite
      case Grant.infra_deploy =>
        Auth.InfraDeploy
      case Grant.infra_destroy =>
        Auth.InfraDestroy
end Grant

/** Issuing and revoking operator tokens.
  *
  * Tokens used to be a list in `MCP_TOKENS`, which meant only whoever deploys could issue one — so
  * giving a translator their own credential was a deployment, and what happened instead was that
  * people shared. These are issued here and stored hashed; see [[Tokens]].
  *
  * The rule is attenuation: a token may only grant scopes it already holds. Nothing a chain of
  * delegations produces can reach further than the token at its root, which is what makes handing
  * `tokens:write` to someone safe — they can extend the chain, never widen it.
  */
object TokenTools:

  final case class CreateRequest(
      @description(
        "Who this is for. Shown in listings and logs, so make it identify a person or a job rather than a date.")
      name: String,
      @description(
        "What it may do. Every one must be a scope you hold yourself — a token cannot grant more than it has.")
      scopes: List[Grant])

  final case class CreateResponse(
      @description(
        "The token. Shown once and never again: only its hash is stored, so a lost one is replaced rather than recovered. Give it to its holder over something private — repeating it in this conversation puts it in the transcript.")
      token: String,
      @description("Addresses it in list_tokens and revoke_token.")
      id: String,
      name: String,
      scopes: List[String])

  final case class ListRequest()

  final case class TokenView(
      id: String,
      name: String,
      scopes: List[String],
      @description("The token that issued this one. Absent for the admin, which nobody issued.")
      createdBy: Option[String],
      createdAt: String,
      @description("A revoked token stays listed: it is what the tokens it issued point at.")
      revoked: Boolean)

  final case class ListResponse(tokens: List[TokenView])

  final case class RevokeRequest(
      @description("Which token. Call list_tokens for the ids.")
      id: String)

  final case class RevokeResponse(id: String, name: String)

  given Schema[CreateRequest] = DeriveSchema.gen
  given Schema[CreateResponse] = DeriveSchema.gen
  given Schema[ListRequest] = DeriveSchema.gen
  given Schema[TokenView] = DeriveSchema.gen
  given Schema[ListResponse] = DeriveSchema.gen
  given Schema[RevokeRequest] = DeriveSchema.gen
  given Schema[RevokeResponse] = DeriveSchema.gen

  private def viewOf(view: Tokens.View): TokenView = TokenView(
    id = view.id,
    name = view.name,
    scopes = view.scopes.map(_.toString).toList.sorted,
    createdBy = view.createdBy,
    createdAt = view.createdAt,
    revoked = view.revoked)

  /** The token behind the call, re-read rather than trusted from the principal.
    *
    * The principal was built when the request arrived; a token revoked in between would still
    * present one. Reading it again is what makes revocation take effect on the next call rather
    * than on the next connection.
    */
  private def caller(ctx: McpToolContext): ZIO[QuillSqlite, String, Tokens.Row] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("No pude identificar el token que hizo la llamada.")
    .flatMap: id =>
      Tokens
        .byIdentifier(id)
        .someOrFail("El token que hizo la llamada ya no existe.")
        .filterOrFail(_.revokedAt.isEmpty)("El token que hizo la llamada fue revocado.")

  val create: McpToolHandlerR[QuillSqlite] = McpTool("create_token")
    .description(
      "Issue a token for another operator. It can only carry scopes you hold yourself. The token is shown once and stored only as a hash, so it cannot be recovered later — hand it over privately rather than repeating it here.")
    .requireScopes(Auth.TokensWrite)
    .handleWithContext: (in: CreateRequest, ctx: McpToolContext) =>
      for
        row <- caller(ctx)
        held      = Tokens.viewOf(row).scopes
        requested = in.scopes.map(Grant.scopeOf).toSet
        _ <- ZIO.when(in.name.trim.isEmpty)(ZIO.fail("El token necesita un nombre."))
        _ <-
          ZIO.when(requested.isEmpty)(
            ZIO.fail("Un token sin scopes no puede hacer nada. Indicá al menos uno."))
        // The whole authorization model, in one line: never more than the issuer holds.
        excess = requested -- held
        _ <-
          ZIO.when(excess.nonEmpty)(
            ZIO.fail(
              s"No podés otorgar ${excess
                  .map(_.toString)
                  .toList
                  .sorted
                  .mkString(
                    ", ")}: tu token no lo tiene. Un token solo puede delegar lo que ya posee."))
        issued <- Tokens.create(in.name.trim, requested, row.id)
      yield CreateResponse(
        token = issued._1,
        id = issued._2.id,
        name = issued._2.name,
        scopes = issued._2.scopes.map(_.toString).toList.sorted)

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_tokens")
    .description(
      "Every operator token: who holds it, what it may do, who issued it and whether it is revoked. Never the token itself — only its hash is stored.")
    .readOnly
    .handle: (_: ListRequest) =>
      Tokens.list.map(views => ListResponse(views.map(viewOf)))

  val revoke: McpToolHandlerR[QuillSqlite] = McpTool("revoke_token")
    .description(
      "Stop a token working. You can revoke tokens you issued; the admin can revoke any. Takes effect on the next call, and also invalidates any configuration page that token had open.")
    .requireScopes(Auth.TokensWrite)
    .handleWithContext: (in: RevokeRequest, ctx: McpToolContext) =>
      for
        row    <- caller(ctx)
        target <- Tokens.byIdentifier(in.id.trim).someOrFail(s"No existe el token '${in.id}'.")
        // Revoking your own is how an operator locks themselves out of the tool that would undo
        // it. Almost always a mistake, and recoverable only through the admin.
        _ <-
          ZIO.when(target.id == row.id)(
            ZIO.fail("Ese es tu propio token. Pedile a otro operador que lo revoque."))
        // The admin is the recovery path for a database that is known to lose data; revoking it
        // would leave a deployment with no way back in until someone redeploys.
        _ <-
          ZIO.when(Tokens.isAdmin(target))(
            ZIO.fail(
              "El token admin no se revoca. Rotalo cambiando MCP_ADMIN_TOKEN y reiniciando."))
        _ <-
          ZIO.when(!Tokens.isAdmin(row) && !target.createdBy.contains(row.id))(
            ZIO.fail(
              s"'${target
                  .name}' no lo emitiste vos. Solo el admin, o quien lo emitió, puede revocarlo."))
        _ <- ZIO.when(target.revokedAt.isDefined)(ZIO.fail(s"'${target.name}' ya estaba revocado."))
        _ <- Tokens.revoke(target.id)
      yield RevokeResponse(target.id, target.name)

  val all: List[McpToolHandlerR[QuillSqlite]] = List(create, list, revoke)
end TokenTools
