package whitelabel.captal.mcp.apps

import java.time.Instant
import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.aws.AssetStore
import whitelabel.captal.mcp.{QuillArrow, Writes}
import whitelabel.captal.platform.{AccessPointRow, LocationRow}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.http.*

import QuillArrow.->

/** Plain HTTP for the pages this server renders, mounted under `/ui` beside the MCP endpoint so the
  * root stays the protocol's.
  *
  * Not MCP: it is how a secret reaches this server without travelling as a tool argument, which the
  * host records and replays into the model's context. Being outside the protocol puts it outside
  * the protocol's authentication too, and [[UiTicket]] is what stands in for it.
  */
object UiRoutes:
  val UnifiPath: String = "/ui/unifi"

  /** One of a location's access points as reported, minus its token. */
  final case class AccessPoint(
      apMac: String,
      host: String,
      port: Option[Int],
      siteId: String,
      tokenSet: Boolean)

  /** What is stored for a location's UniFi, minus the tokens: the read `configure_unifi` reports
    * and `get_unifi_config` answers with, kept in one place so the two cannot disagree. The access
    * duration, the redirect URL and the switch are the location's; host, port, site id and token
    * are each access point's, since a venue's APs need not share a controller.
    */
  final case class UnifiSettings(
      slug: String,
      name: String,
      redirectUrl: Option[String],
      durationMinutes: Option[Int],
      enabled: Boolean,
      accessPoints: List[AccessPoint])

  private inline def bySlug = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam)

  private inline def pointsOf = quote: (locationParam: String) =>
    query[AccessPointRow].filter(_.locationId == locationParam)

  private inline def pointOf = quote: (locationParam: String, macParam: String) =>
    query[AccessPointRow].filter(ap => ap.locationId == locationParam && ap.mac == macParam)

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

  /** `raw` as `access_points.mac` stores it — lowercase, with colons — or why it is not a MAC. */
  private[mcp] def macOf(raw: String): Either[String, String] = Mac
    .parse(raw.trim)
    .map(_.show)
    .toRight(
      s"'${raw
          .trim}' no es una MAC: seis pares de dígitos hexadecimales, separados por ':' o '-' o juntos.")

  private def locationFor(slug: String): ZIO[QuillSqlite, String, LocationRow] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(bySlug(lift(slug))).orDie.map(_.headOption).someOrFail(s"No existe la location '$slug'.")

  /** Shared with `configure_unifi`, so the page is filled from the same read the tool reports. */
  private[mcp] def currentFor(slug: String): ZIO[QuillSqlite, String, UnifiSettings] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        row    <- locationFor(slug)
        points <- run(pointsOf(lift(row.id))).orDie
      yield UnifiSettings(
        slug = row.slug,
        name = row.name,
        redirectUrl = row.unifiRedirectUrl,
        durationMinutes = row.unifiDurationMinutes,
        enabled = row.unifiIsEnabled == 1,
        accessPoints = points
          .sortBy(_.mac)
          .map(point =>
            AccessPoint(
              apMac = point.mac,
              host = point.unifiHost,
              port = point.unifiPort,
              siteId = point.unifiSiteId,
              // Whether, never what. The value leaves this server only towards the controller.
              tokenSet = point.unifiApiToken.nonEmpty
            ))
      )
      end for

  /** Turns the location's UniFi calls on or off, leaving its stored settings as they are. */
  private[mcp] def setEnabled(
      slug: String,
      enabled: Boolean): ZIO[QuillSqlite, String, UnifiSettings] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      val flag =
        if enabled then
          1
        else
          0
      for
        _        <- locationFor(slug)
        affected <-
          run(
            bySlug(lift(slug)).update(
              _.unifiIsEnabled -> lift(flag),
              _.updatedAt      -> lift(Instant.now.toString))).orDie
        _       <- Writes.expectOne(affected, s"Cambiando UniFi de '$slug'")
        current <- currentFor(slug)
      yield current

  /** Stores the location's own settings, the redirect URL and the access duration, as given. */
  private[mcp] def storeLocationSettings(
      slug: String,
      redirectUrl: Option[String],
      durationMinutes: Option[Int])
      : ZIO[QuillSqlite, String, UnifiSettings] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    for
      affected <-
        run(
          bySlug(lift(slug)).update(
            _.unifiRedirectUrl     -> lift(redirectUrl),
            _.unifiDurationMinutes -> lift(durationMinutes),
            _.updatedAt            -> lift(Instant.now.toString))).orDie
      _       <- Writes.expectOne(affected, s"Cambiando UniFi de '$slug'")
      current <- currentFor(slug)
    yield current

  /** The location's access point with this MAC, or why there is none. */
  private def pointFor(
      location: LocationRow,
      mac: String): ZIO[QuillSqlite, String, AccessPointRow] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(pointOf(lift(location.id), lift(mac)))
        .orDie
        .map(_.headOption)
        .someOrFail(
          s"'${location.slug}' no tiene un AP con la MAC $mac. get_unifi_config los lista.")

  /** Stores an access point's settings that are not secret, as `point` gives them — its MAC among
    * them, which may change — leaving its token as it is.
    */
  private[mcp] def storeAccessPoint(
      slug: String,
      mac: String,
      point: AccessPoint): ZIO[QuillSqlite & AssetStore, String, UnifiSettings] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        location <- locationFor(slug)
        _        <- pointFor(location, mac)
        _        <-
          ZIO.when(point.apMac != mac)(
            run(pointOf(lift(location.id), lift(point.apMac)))
              .orDie
              .filterOrFail(_.isEmpty)(s"'$slug' ya tiene un AP con la MAC ${point.apMac}.") *>
              refuseRunningElsewhere(slug, point.apMac))
        affected <-
          run(
            pointOf(lift(location.id), lift(mac)).update(
              _.mac         -> lift(point.apMac),
              _.unifiHost   -> lift(point.host),
              _.unifiPort   -> lift(point.port),
              _.unifiSiteId -> lift(point.siteId),
              _.updatedAt   -> lift(Instant.now.toString)
            )).orDie
        _       <- Writes.expectOne(affected, s"Cambiando el AP $mac de '$slug'")
        current <- currentFor(slug)
      yield current
      end for

  /** Takes an access point off the location; a guest who arrives through it afterwards is turned
    * away.
    */
  private[mcp] def removeAccessPoint(
      slug: String,
      mac: String): ZIO[QuillSqlite, String, UnifiSettings] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      for
        location <- locationFor(slug)
        _        <- pointFor(location, mac)
        affected <- run(pointOf(lift(location.id), lift(mac)).delete).orDie
        _        <- Writes.expectOne(affected, s"Quitando el AP $mac de '$slug'")
        current  <- currentFor(slug)
      yield current

  /** Refuses `mac` to `slug` while another running location has it: an access point takes the
    * guests of one running location, which is what the portal dispatcher counts on. Running is
    * active and not paused — a paused location's page is the maintenance page, and its access
    * points are free to take.
    */
  private[mcp] def refuseRunningElsewhere(
      slug: String,
      mac: String): ZIO[QuillSqlite & AssetStore, String, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      for
        holders <- run(query[AccessPointRow].filter(_.mac == lift(mac)).map(_.locationId)).orDie
        others  <- ZIO
          .foreach(holders.distinct)(id => run(query[LocationRow].filter(_.id == lift(id))).orDie)
          .map(_.flatten.filter(other => other.slug != slug && other.isActive == 1))
        assets  <- ZIO.service[AssetStore]
        running <- ZIO.filter(others)(other => assets.isPaused(other.slug).map(!_))
        _       <-
          ZIO.foreachDiscard(running.headOption)(other =>
            ZIO.fail(
              s"El AP $mac ya está en '${other
                  .slug}', que está activa: un AP lleva a los invitados de una sola location activa. Pausala con pause_location antes de usarlo en '$slug'."))
      yield ()

  /** Refuses `slug` while another running location shares one of its access points: what
    * `deploy_location` and `resume_location` ask before a location starts taking guests.
    */
  private[mcp] def refuseSharedAccessPoints(
      slug: String): ZIO[QuillSqlite & AssetStore, String, Unit] = currentFor(slug).flatMap(
    current =>
      ZIO.foreachDiscard(current.accessPoints)(point => refuseRunningElsewhere(slug, point.apMac)))

  /** Registers the access point the page names, or changes it, with its controller's settings, and
    * the location's access duration and redirect URL beside them.
    */
  private def save(slug: String, form: Form): ZIO[QuillSqlite & AssetStore, String, String] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        location <- locationFor(slug).orElseFail(s"La location '$slug' ya no existe.")
        rawMac   <- ZIO.fromOption(field(form, "apMac")).orElseFail("Falta la MAC del AP.")
        mac      <- ZIO.fromEither(macOf(rawMac))
        host     <- ZIO.fromOption(field(form, "host")).orElseFail("Falta el host.")
        siteId   <- ZIO.fromOption(field(form, "siteId")).orElseFail("Falta el site id.")
        existing <- run(pointOf(lift(location.id), lift(mac))).orDie.map(_.headOption)
        // Kept when the field is blank, which is how the page avoids ever rendering it back; an AP
        // with no token stored yet has nothing to keep, so there the field is required.
        token <- ZIO
          .fromOption(field(form, "apiToken").orElse(existing.map(_.unifiApiToken)))
          .orElseFail(s"Falta el API token: el AP $mac todavía no tiene uno.")
        port     <- ZIO.fromEither(number(form, "port", "El puerto"))
        duration <- ZIO.fromEither(number(form, "durationMinutes", "La duración"))
        _        <- refuseRunningElsewhere(slug, mac)
        now = Instant.now.toString
        written <-
          existing match
            case Some(_) =>
              run(
                pointOf(lift(location.id), lift(mac)).update(
                  _.unifiHost     -> lift(host),
                  _.unifiPort     -> lift(port),
                  _.unifiSiteId   -> lift(siteId),
                  _.unifiApiToken -> lift(token),
                  _.updatedAt     -> lift(now))).orDie
            case None =>
              run(
                query[AccessPointRow].insertValue(
                  lift(
                    AccessPointRow(
                      id = UUID.randomUUID.toString,
                      locationId = location.id,
                      mac = mac,
                      unifiHost = host,
                      unifiPort = port,
                      unifiSiteId = siteId,
                      unifiApiToken = token,
                      createdAt = now,
                      updatedAt = now
                    )))).orDie
        _        <- Writes.expectOne(written, s"Guardando el AP $mac de '$slug'")
        affected <-
          run(
            bySlug(lift(slug)).update(
              _.unifiDurationMinutes -> lift(duration),
              _.unifiRedirectUrl     -> lift(field(form, "redirectUrl")),
              _.updatedAt            -> lift(now))).orDie
        _ <- Writes.expectOne(affected, s"Configurando UniFi para '$slug'")
      // Says what changed without saying what it changed to: this body is shown on the page.
      yield s"Listo. El AP $mac de '$slug' queda configurado contra $host."
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

  def routes: Routes[QuillSqlite & AssetStore, Response] = Routes(
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
