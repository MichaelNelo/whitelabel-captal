package whitelabel.captal.mcp.apps

import java.time.Instant
import java.util.UUID

import whitelabel.captal.core.application.commands.{
  RegisterAccessPointCommand,
  UpdateControllerCommand
}
import whitelabel.captal.core.application.queries.{AccessPointsOfQuery, Taker}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{ControllerChoice, ControllerId, Secret, SettingsChange}
import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.tools.{Commands, LocationFlows, Queries, Refusals}
import whitelabel.captal.platform.aws.AssetStore
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

  /** One of a location's access points as reported, minus its controller's token: its controller,
    * what the portal does for the guests who join through it, and the location that took it.
    */
  final case class AccessPoint(
      apMac: String,
      controllerId: String,
      host: String,
      port: Option[Int],
      siteId: String,
      tokenSet: Boolean,
      durationMinutes: Option[Int],
      redirectUrl: Option[String],
      enabled: Boolean,
      takenBy: Option[Taker])

  /** What is stored for a location's UniFi, minus the tokens: the read `configure_unifi` reports
    * and `get_unifi_config` answers with, kept in one place so the two cannot disagree. All of it
    * is each access point's, since a venue's APs need not share a controller.
    */
  final case class UnifiSettings(slug: String, name: String, accessPoints: List[AccessPoint])

  /** What the page posts for the controller: `NewController` for a new one, made from the fields
    * beside it; any other value, the id of one the system has; blank, the access point's own.
    */
  val NewController: String = "new"

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
  private[mcp] def currentFor(slug: String): ZIO[Queries.AccessPointsOf, String, UnifiSettings] =
    for
      parsed <- Refusals.slugOf(slug)
      report <- ZIO.serviceWithZIO[Queries.AccessPointsOf](
        Commands.ask(_, AccessPointsOfQuery(parsed))())
    yield UnifiSettings(
      slug = report.slug,
      name = report.name,
      accessPoints = report
        .accessPoints
        .map(point =>
          AccessPoint(
            apMac = point.apMac,
            controllerId = point.controllerId,
            host = point.host,
            port = point.port,
            siteId = point.siteId,
            tokenSet = point.tokenSet,
            durationMinutes = point.durationMinutes,
            redirectUrl = point.redirectUrl,
            enabled = point.enabled,
            takenBy = point.takenBy
          ))
    )

  /** The controller the page chose ([[NewController]]): a new one needs its host, site id and
    * token.
    */
  private def chosen(form: Form): IO[String, Option[ControllerChoice]] =
    field(form, "controllerId") match
      case None =>
        ZIO.none
      case Some(NewController) =>
        for
          host   <- ZIO.fromOption(field(form, "host")).orElseFail("Falta el host del controlador.")
          siteId <- ZIO
            .fromOption(field(form, "siteId"))
            .orElseFail("Falta el site id del controlador.")
          port  <- ZIO.fromEither(number(form, "port", "El puerto"))
          token <- ZIO
            .fromOption(field(form, "apiToken").flatMap(Secret.parse))
            .orElseFail("Falta el API token del controlador nuevo.")
        yield Some(
          ControllerChoice.New(
            ControllerId(s"controller-${UUID.randomUUID}"),
            host,
            port,
            siteId,
            token))
      case Some(id) =>
        ZIO.some(ControllerChoice.Existing(ControllerId(id)))

  /** Configures the location with the access point the page names, or changes how — the controller
    * of a new one, the access duration and the redirect URL — as the operator token `actor`. A
    * token given with a controller the system has replaces that controller's, for every access
    * point that answers to it; blank, it keeps the one stored, which is how the page avoids ever
    * rendering it back. A blank duration or redirect URL clears it.
    */
  private def save(slug: String, form: Form, actor: String): ZIO[
    OperatorContext & LocationFlows.RegisterAccessPoint & LocationFlows.UpdateController,
    String,
    String] =
    // The page's post, with no MCP call behind it to ask anything through.
    val operator = Operator(actor, None)
    for
      place      <- Refusals.slugOf(slug)
      rawMac     <- ZIO.fromOption(field(form, "apMac")).orElseFail("Falta la MAC del AP.")
      mac        <- ZIO.fromEither(parsedMac(rawMac))
      controller <- chosen(form)
      duration   <- ZIO.fromEither(number(form, "durationMinutes", "La duración"))
      register   <- ZIO.service[LocationFlows.RegisterAccessPoint]
      point      <-
        Commands.run(
          operator,
          RegisterAccessPointCommand(
            place,
            mac,
            controller,
            SettingsChange(
              durationMinutes = Some(duration),
              redirectUrl = Some(field(form, "redirectUrl"))),
            Instant.now)
        )(register)()
      retokened <-
        ZIO.foreach(
          controller
            .collect { case ControllerChoice.Existing(id) =>
              id
            }
            .zip(field(form, "apiToken").flatMap(Secret.parse))): (id, token) =>
          ZIO.serviceWithZIO[LocationFlows.UpdateController](
            Commands.run(
              operator,
              UpdateControllerCommand(id, None, None, None, Some(token), Instant.now))(_)())
    // Says what changed without saying what it changed to: this body is shown on the page.
    yield s"Listo. El AP ${mac.show} de '$slug' queda configurado contra ${point.controller.host}" +
      retokened.fold(".")(_ => ", con el token nuevo del controlador.")
    end for
  end save

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
    QuillSqlite & AssetStore & OperatorContext & LocationFlows.RegisterAccessPoint &
      LocationFlows.UpdateController,
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
