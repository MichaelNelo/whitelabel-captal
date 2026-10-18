package whitelabel.captal.mcp.apps

import java.time.Instant

import io.getquill.*
import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.{QuillArrow, Writes}
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.http.*

import QuillArrow.->

/** Plain HTTP for the pages this server renders, mounted beside the MCP endpoint.
  *
  * Under `/ui` so the root stays the protocol's. This route is not MCP and deliberately so: a
  * secret that travels as a tool argument is recorded by the host and replayed into the model's
  * context, and the only way to keep it out of both is to let the page talk to this server directly
  * — the same move the upload page makes when it PUTs a file to S3 rather than sending it over
  * JSON-RPC.
  *
  * Being outside the protocol puts it outside the protocol's authentication too, which is what
  * [[UiTicket]] replaces. The ticket is not a credential of its own: it is minted only for a caller
  * that already presented an operator token carrying `content:write`, it names one location, and it
  * expires in minutes.
  */
object UiRoutes:
  val UnifiPath: String = "/ui/unifi"

  /** What is stored for a location, minus the token.
    *
    * Here rather than beside the page because the page no longer receives it — it is an App now and
    * reads everything from the result of the call that opened it. This is the read
    * `configure_unifi` reports and `get_unifi_config` answers with, kept in one place so the two
    * cannot disagree.
    */
  final case class UnifiSettings(
      slug: String,
      name: String,
      host: Option[String],
      port: Option[Int],
      siteId: Option[String],
      apMac: Option[String],
      redirectUrl: Option[String],
      durationMinutes: Option[Int],
      tokenSet: Boolean)

  private inline def bySlug = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam)

  /** Blank means "leave it alone", which is what lets the page render without the token it will
    * never read back. A field the operator clears is therefore not a way to unset a value — that
    * would make an accidental empty submission destructive, and unsetting is not what this is for.
    */
  private def field(form: Form, name: String): Option[String] = form
    .get(name)
    .flatMap(_.stringValue)
    .map(_.trim)
    .filter(_.nonEmpty)

  /** The page lives in a sandboxed iframe, so its origin is opaque and every request from it is
    * cross-origin. `*` is safe here precisely because the ticket is the authorization: there is no
    * cookie or header the browser would attach on its own, so a page on another origin gains
    * nothing by being allowed to call this — it would still need a ticket this server minted.
    */
  private def text(status: Status, body: String): Response = Response(
    status = status,
    headers =
      Headers(Header.ContentType(MediaType.text.plain)) ++
        Headers(Header.AccessControlAllowOrigin.All),
    body = Body.fromString(body))

  /** Parsed rather than accepted as-is, so a typo lands as a message on the page instead of a
    * column that silently stops `UnifiAccess.fromRow` from building.
    */
  private def number(form: Form, name: String, label: String): Either[String, Option[Int]] =
    field(form, name) match
      case None =>
        Right(None)
      case Some(raw) =>
        raw.toIntOption.filter(_ > 0).map(Some(_)).toRight(s"$label debe ser un número positivo.")

  /** Shared with `configure_unifi`, so the page is filled from the same read the tool reports — a
    * resource and the tool that answers the same question cannot drift apart.
    */
  private[mcp] def currentFor(slug: String): ZIO[QuillSqlite, String, UnifiSettings] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(bySlug(lift(slug)))
        .orDie
        .map(_.headOption)
        .someOrFail(s"No existe la location '$slug'.")
        .map: row =>
          UnifiSettings(
            slug = row.slug,
            name = row.name,
            host = row.unifiHost,
            port = row.unifiPort,
            siteId = row.unifiSiteId,
            apMac = row.unifiApMac,
            redirectUrl = row.unifiRedirectUrl,
            durationMinutes = row.unifiDurationMinutes,
            // Whether, never what. The value leaves this server only towards the UniFi controller.
            tokenSet = row.unifiApiToken.isDefined
          )

  private def save(slug: String, form: Form): ZIO[QuillSqlite, String, String] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    for
      rows   <- run(bySlug(lift(slug))).orDie
      row    <- ZIO.succeed(rows.headOption).someOrFail(s"La location '$slug' ya no existe.")
      host   <- ZIO.fromOption(field(form, "host")).orElseFail("Falta el host.")
      siteId <- ZIO.fromOption(field(form, "siteId")).orElseFail("Falta el site id.")
      // Kept when the field is blank, which is how the page avoids ever rendering it back. A
      // location with no token yet has nothing to keep, so there the field is genuinely required.
      token <- ZIO
        .fromOption(field(form, "apiToken").orElse(row.unifiApiToken))
        .orElseFail("Falta el API token.")
      port     <- ZIO.fromEither(number(form, "port", "El puerto"))
      duration <- ZIO.fromEither(number(form, "durationMinutes", "La duración"))
      now = Instant.now.toString
      affected <-
        run(
          bySlug(lift(slug)).update(
            _.unifiHost            -> lift(Option(host)),
            _.unifiApiToken        -> lift(Option(token)),
            _.unifiSiteId          -> lift(Option(siteId)),
            _.unifiPort            -> lift(port),
            _.unifiDurationMinutes -> lift(duration),
            _.unifiApMac           -> lift(field(form, "apMac")),
            _.unifiRedirectUrl     -> lift(field(form, "redirectUrl")),
            _.updatedAt            -> lift(now)
          )).orDie
      _ <- Writes.expectOne(affected, s"Configurando UniFi para '$slug'")
    // Says what changed without saying what it changed to. This body is shown on the page, and
    // the operator is the one person who already knows the value.
    yield s"Listo. '$slug' queda configurada contra $host."
    end for

  /** Expiry is separated because it is the one an honest operator hits and the remedy differs.
    * Everything else answers the same way: distinguishing "forged" from "revoked" would tell
    * whoever is probing which half to work on.
    */
  private def rejected(reason: UiTicket.Rejected): Response =
    reason match
      case UiTicket.Rejected.Expired =>
        text(Status.Forbidden, "Esta página caducó. Pedí otra con configure_unifi.")
      case _ =>
        text(Status.Forbidden, "Esta página no es válida. Pedí otra con configure_unifi.")

  /** Finds the key the ticket was signed with, which is the calling token's stored hash.
    *
    * A revoked token verifies nothing: the row stays for the record, so this is where it stops
    * being usable. That is the point of keying on the token rather than on a server-wide secret —
    * revocation reaches the pages that token opened, without touching anybody else's.
    */
  private def slugFor(ticket: String): ZIO[QuillSqlite, UiTicket.Rejected, String] =
    UiTicket.tokenIdOf(ticket) match
      case None =>
        ZIO.fail(UiTicket.Rejected.Malformed)
      case Some(tokenId) =>
        Tokens
          .byIdentifier(tokenId)
          .flatMap:
            case Some(row) if row.revokedAt.isEmpty =>
              ZIO.fromEither(UiTicket.verify(row.tokenSha256, ticket, Instant.now))
            case _ =>
              ZIO.fail(UiTicket.Rejected.UnknownToken)

  def routes: Routes[QuillSqlite, Response] = Routes(
    Method.POST / "ui" / "unifi" ->
      handler: (request: Request) =>
        request
          .body
          .asURLEncodedForm
          .orElseSucceed(Form.empty)
          .flatMap: form =>
            field(form, "ticket") match
              case None =>
                ZIO.succeed(rejected(UiTicket.Rejected.Malformed))
              case Some(ticket) =>
                slugFor(ticket)
                  .flatMap: slug =>
                    save(slug, form)
                      .map(text(Status.Ok, _))
                      .catchAll(message => ZIO.succeed(text(Status.BadRequest, message)))
                  .catchAll(reason => ZIO.succeed(rejected(reason))))
end UiRoutes
