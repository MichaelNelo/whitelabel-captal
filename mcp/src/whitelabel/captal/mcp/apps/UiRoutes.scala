package whitelabel.captal.mcp.apps

import java.time.Instant

import cats.syntax.foldable.*
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.commands.RegisterAccessPointCommand
import whitelabel.captal.core.application.queries.AccessPointsOfQuery
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{Secret, SettingsChange, Slug}
import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.tools.{Commands, LocationCommands, LocationFlows}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.queries.Locations
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.http.*

/** Plain HTTP for the pages this server renders, mounted under `/ui` beside the MCP endpoint so the
  * root stays the protocol's.
  *
  * Not MCP: it is how a secret reaches this server without travelling as a tool argument, which the
  * host records and replays into the model's context. Being outside the protocol puts it outside
  * the protocol's authentication too, and [[UiTicket]] is what stands in for it.
  */
object UiRoutes:
  val UnifiPath: String = "/ui/unifi"

  /** One of a location's access points as reported, minus its token: its controller, and what the
    * portal does for the guests who join through it.
    */
  final case class AccessPoint(
      apMac: String,
      host: String,
      port: Option[Int],
      siteId: String,
      tokenSet: Boolean,
      durationMinutes: Option[Int],
      redirectUrl: Option[String],
      enabled: Boolean)

  /** What is stored for a location's UniFi, minus the tokens: the read `configure_unifi` reports
    * and `get_unifi_config` answers with, kept in one place so the two cannot disagree. All of it
    * is each access point's, since a venue's APs need not share a controller.
    */
  final case class UnifiSettings(slug: String, name: String, accessPoints: List[AccessPoint])

  /** Blank means "leave it alone", which is what lets the page render without the token it will
    * never read back. A field the operator clears is not a way to unset a value.
    */
  private def field(form: Form, name: String): Option[String] = form
    .get(name)
    .flatMap(_.stringValue)
    .map(_.trim)
    .filter(_.nonEmpty)

  /** A plain-text response with `Access-Control-Allow-Origin: *`, which the sandboxed iframe's
    * opaque origin needs and the ticket — not the origin — is what authorizes.
    */
  private def text(status: Status, body: String): Response = Response(
    status = status,
    headers =
      Headers(Header.ContentType(MediaType.text.plain)) ++
        Headers(Header.AccessControlAllowOrigin.All),
    body = Body.fromString(body))

  /** A positive int or nothing: parsed rather than accepted as-is, so a typo lands as a message on
    * the page instead of a column the portal cannot use.
    */
  private def number(form: Form, name: String, label: String): Either[String, Option[Int]] =
    field(form, name) match
      case None =>
        Right(None)
      case Some(raw) =>
        raw.toIntOption.filter(_ > 0).map(Some(_)).toRight(s"$label debe ser un número positivo.")

  /** The MAC `raw` names, or why it is not one. */
  private[mcp] def parsedMac(raw: String): Either[String, Mac] = Mac
    .parse(raw.trim)
    .toRight(
      s"'${raw
          .trim}' no es una MAC: seis pares de dígitos hexadecimales, separados por ':' o '-' o juntos.")

  /** `raw` as `access_points.mac` stores it — lowercase, with colons — or why it is not a MAC. */
  private[mcp] def macOf(raw: String): Either[String, String] = parsedMac(raw).map(_.show)

  /** Shared with `configure_unifi`, so the page is filled from the same read the tool reports:
    * `AccessPointsOf`.
    */
  private[mcp] def currentFor(slug: String): ZIO[QuillSqlite, String, UnifiSettings] =
    for
      parsed <- ZIO.fromEither(Slug.parse(slug)).mapError(LocationCommands.describe)
      quill  <- ZIO.service[QuillSqlite]
      report <-
        Commands.ask(Locations.accessPoints(quill), AccessPointsOfQuery(parsed)) {
          case AppError.Location(found) =>
            found.toList.map(LocationCommands.describe)
        }
    yield UnifiSettings(
      slug = report.slug,
      name = report.name,
      accessPoints = report
        .accessPoints
        .map(point =>
          AccessPoint(
            apMac = point.apMac,
            host = point.host,
            port = point.port,
            siteId = point.siteId,
            tokenSet = point.tokenSet,
            durationMinutes = point.durationMinutes,
            redirectUrl = point.redirectUrl,
            enabled = point.enabled
          ))
    )

  /** Registers the access point the page names, or changes it — its controller, the access duration
    * and the redirect URL — as the operator token `actor`. A blank token keeps the one stored,
    * which is how the page avoids ever rendering it back; a blank duration or redirect URL clears
    * it.
    */
  private def save(
      slug: String,
      form: Form,
      actor: String): ZIO[OperatorContext & LocationFlows.RegisterAccessPoint, String, String] =
    for
      place    <- ZIO.fromEither(Slug.parse(slug)).mapError(LocationCommands.describe)
      rawMac   <- ZIO.fromOption(field(form, "apMac")).orElseFail("Falta la MAC del AP.")
      mac      <- ZIO.fromEither(parsedMac(rawMac))
      host     <- ZIO.fromOption(field(form, "host")).orElseFail("Falta el host.")
      siteId   <- ZIO.fromOption(field(form, "siteId")).orElseFail("Falta el site id.")
      port     <- ZIO.fromEither(number(form, "port", "El puerto"))
      duration <- ZIO.fromEither(number(form, "durationMinutes", "La duración"))
      flow     <- ZIO.service[LocationFlows.RegisterAccessPoint]
      _        <-
        // The page's post, with no MCP call behind it to ask anything through.
        LocationCommands.run(
          Operator(actor, None),
          RegisterAccessPointCommand(
            place,
            mac,
            host,
            port,
            siteId,
            field(form, "apiToken").flatMap(Secret.parse),
            SettingsChange(
              durationMinutes = Some(duration),
              redirectUrl = Some(field(form, "redirectUrl"))),
            Instant.now
          )
        )(flow)
    // Says what changed without saying what it changed to: this body is shown on the page.
    yield s"Listo. El AP ${mac.show} de '$slug' queda configurado contra $host."

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
  /** The token that opened the page, which the change is recorded under, and the location it was
    * opened for.
    */
  private def slugFor(ticket: String): ZIO[QuillSqlite, UiTicket.Rejected, (String, String)] =
    UiTicket.tokenIdOf(ticket) match
      case None =>
        ZIO.fail(UiTicket.Rejected.Malformed)
      case Some(tokenId) =>
        Tokens
          .byIdentifier(tokenId)
          .flatMap:
            case Some(row) if row.revokedAt.isEmpty =>
              ZIO
                .fromEither(UiTicket.verify(row.tokenSha256, ticket, Instant.now))
                .map(slug => (tokenId, slug))
            case _ =>
              ZIO.fail(UiTicket.Rejected.UnknownToken)

  def routes: Routes[
    QuillSqlite & AssetStore & OperatorContext & LocationFlows.RegisterAccessPoint,
    Response] = Routes(
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
                  .flatMap: (actor, slug) =>
                    save(slug, form, actor)
                      .map(text(Status.Ok, _))
                      .catchAll(message => ZIO.succeed(text(Status.BadRequest, message)))
                  .catchAll(reason => ZIO.succeed(rejected(reason))))
end UiRoutes
