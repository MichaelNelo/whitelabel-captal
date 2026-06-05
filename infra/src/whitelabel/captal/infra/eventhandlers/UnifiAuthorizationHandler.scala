package whitelabel.captal.infra.eventhandlers

import io.circe.{Decoder, Json, parser as circeParser}
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.infra.UnifiAccess
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig

/** Event handler that listens for [[whitelabel.captal.core.user.Event.UserFinishedProcess]] and
  * grants internet access to the client's MAC for `defaultDurationMinutes` minutes via the UniFi
  * Network Integration v1 API.
  *
  * The Integration v1 flow is two-step:
  *   1. `GET /proxy/network/integration/v1/sites/{siteId}/clients?filter=macAddress.eq('AA:BB:..')`
  *      → extract `clientId` from the response (single match expected for an active client).
  *   2. `POST /proxy/network/integration/v1/sites/{siteId}/clients/{clientId}/actions` with body
  *      `{"action": "AUTHORIZE_GUEST_ACCESS", "timeLimitMinutes": <n>}`.
  *
  * The legacy `/api/s/{site}/cmd/stamgr` endpoint with `authorize-guest` payload is no longer used.
  *
  * Lives outside the transactional event handler chain (composed via `.andThen` after the
  * transaction commits) so HTTP failures don't roll back DB writes.
  *
  * Behaviour:
  *   - If no [[UnifiAccess]] is configured for the location → logs and skips.
  *   - On UniFi 2xx (both calls) → marks the session [[Phase.Authorized]] with `expiresAt =
  *     occurredAt + defaultDurationMinutes`.
  *   - On any UniFi failure (timeout, non-2xx, client not found, parse error) → logs and skips. The
  *     session remains in [[Phase.Ready]]; the user can retry via `/api/finish` again.
  */
object UnifiAuthorizationHandler:

  /** HTTP client layer that trusts ALL TLS certs and (optionally) routes via an HTTP proxy.
    *
    * UniFi Controllers ship with self-signed certs by default and the connection to them goes via a
    * private overlay (Tailscale), so transport security is provided by the overlay rather than the
    * cert. In zio-http 3.x, `ClientSSLConfig.Default` is implemented with
    * `InsecureTrustManagerFactory.INSTANCE` — i.e. trust-all. We set `ssl = Some(Default)` to force
    * HTTPS connections through that path (when `ssl = None`, Netty uses the JDK default trust store
    * which rejects self-signed).
    *
    * The optional `proxyUrl` parameter is needed in production deploys where the API runs on ECS
    * Fargate and reaches the on-premise UCG via `tailscale-proxy (ECS daemon) → Tailscale subnet
    * router (VM) → LAN`. In local/test, leave it `None` for direct connection.
    */
  def trustAllClientLayer(proxyUrl: Option[String]): ZLayer[Any, Throwable, Client] =
    val configLayer: ZLayer[Any, Throwable, ZClient.Config] = ZLayer.fromZIO:
      val proxyOpt: ZIO[Any, Throwable, Option[Proxy]] =
        proxyUrl match
          case None =>
            ZIO.none
          case Some(raw) =>
            ZIO
              .fromEither(URL.decode(raw))
              .mapError(e =>
                new RuntimeException(s"Invalid UNIFI_PROXY_URL '$raw': ${e.getMessage}"))
              .map(u => Some(Proxy(u)))
      proxyOpt.map: proxy =>
        ZClient.Config.default.copy(ssl = Some(ClientSSLConfig.Default), proxy = proxy)
    (configLayer ++ ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default) >>>
      ZClient.live

  def apply(
      unifi: Option[UnifiAccess],
      ctx: SessionContext,
      sessionService: SessionService,
      client: Client): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        val finishedOpt = events.collectFirst {
          case Event.User(UserEvent.UserFinishedProcess(_, _, _, occurredAt)) =>
            occurredAt
        }
        finishedOpt match
          case None =>
            ZIO.unit
          case Some(occurredAt) =>
            unifi match
              case None =>
                ZIO.logWarning(
                  "UniFi authorize: no config for current location — skipping authorization")
              case Some(unifiCfg) =>
                for
                  session <- ctx.getOrFail
                  expiresAt = occurredAt.plusSeconds(unifiCfg.defaultDurationMinutes * 60L)
                  result <- authorizeGuest(client, unifiCfg, session.clientMac).either
                  _      <-
                    result match
                      case Left(error) =>
                        ZIO.logError(
                          s"UniFi authorize failed for session ${session
                              .sessionId
                              .asString}: ${error.getMessage}")
                      case Right(_) =>
                        sessionService.setAuthorized(session.sessionId, expiresAt) *>
                          ZIO.logInfo(
                            s"UniFi authorized session ${session
                                .sessionId
                                .asString} until $expiresAt")
                yield ()
        end match
      end handle

  /** Two-step Integration v1 flow: look up clientId by MAC, then POST the AUTHORIZE_GUEST_ACCESS
    * action. Both calls share the same X-API-KEY auth header.
    */
  private def authorizeGuest(client: Client, unifi: UnifiAccess, clientMac: String): Task[Unit] =
    // IPv6 hosts (e.g. Tailscale ULA fd7a:115c:...) must be bracketed in URLs per RFC 3986.
    // Without brackets the `:` of the address mixes with the port separator and zio-http
    // rejects the URL with "Invalid URL". Detect IPv6 by the presence of a colon (IPv4 and
    // DNS names never contain one) and wrap accordingly.
    val hostFmt = if unifi.host.contains(":") then s"[${unifi.host}]" else unifi.host
    val base = s"https://$hostFmt:${unifi.port}/proxy/network/integration/v1"
    val macNormalized = clientMac.toLowerCase.replace("-", ":")
    for
      clientId <- findClientId(client, base, unifi.siteId, unifi.apiToken, macNormalized)
      _        <- postAuthorize(
        client,
        base,
        unifi.siteId,
        clientId,
        unifi.apiToken,
        unifi.defaultDurationMinutes)
    yield ()

  /** GET /sites/{siteId}/clients?filter=macAddress.eq('mac') — returns a paginated list; we expect
    * exactly one match for a connected guest about to be authorized.
    */
  private def findClientId(
      client: Client,
      base: String,
      siteId: String,
      apiToken: String,
      macLower: String): Task[String] =
    val filter = s"macAddress.eq('$macLower')"
    val urlStr =
      s"$base/sites/$siteId/clients?filter=${java.net.URLEncoder.encode(filter, "UTF-8")}"
    for
      url <- ZIO
        .fromEither(URL.decode(urlStr))
        .mapError(e => new RuntimeException(s"Invalid UniFi URL '$urlStr': ${e.getMessage}"))
      request = Request.get(url).addHeader("X-API-KEY", apiToken)
      response <- client.batched(request)
      body     <- response.body.asString
      _        <-
        ZIO.when(response.status.code < 200 || response.status.code >= 300)(
          ZIO.fail(
            new RuntimeException(s"UniFi GET /clients returned ${response.status.code}: $body")))
      clientId <- ZIO
        .fromEither(extractClientId(body, macLower))
        .mapError(e => new RuntimeException(s"UniFi /clients parse failed: $e"))
    yield clientId
  end findClientId

  /** Decode the JSON envelope `{"data": [{"id": "...", ...}, ...]}` and return the first id. */
  private def extractClientId(body: String, macLower: String): Either[String, String] =
    given Decoder[String] = Decoder.decodeString
    circeParser
      .parse(body)
      .left
      .map(_.getMessage)
      .flatMap: json =>
        val ids =
          json.hcursor.downField("data").values match
            case Some(arr) =>
              arr.toList.flatMap(_.hcursor.get[String]("id").toOption)
            case None =>
              Nil
        ids.headOption.toRight(s"no client found for MAC $macLower")

  /** POST /sites/{siteId}/clients/{clientId}/actions with AUTHORIZE_GUEST_ACCESS. */
  private def postAuthorize(
      client: Client,
      base: String,
      siteId: String,
      clientId: String,
      apiToken: String,
      durationMinutes: Int): Task[Unit] =
    val urlStr = s"$base/sites/$siteId/clients/$clientId/actions"
    val bodyJson =
      Json
        .obj(
          "action"           -> Json.fromString("AUTHORIZE_GUEST_ACCESS"),
          "timeLimitMinutes" -> Json.fromInt(durationMinutes))
        .noSpaces
    for
      url <- ZIO
        .fromEither(URL.decode(urlStr))
        .mapError(e => new RuntimeException(s"Invalid UniFi URL '$urlStr': ${e.getMessage}"))
      request = Request
        .post(url, Body.fromString(bodyJson))
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader("X-API-KEY", apiToken)
      response <- client.batched(request)
      _        <-
        ZIO.when(response.status.code < 200 || response.status.code >= 300)(
          response
            .body
            .asString
            .flatMap: body =>
              ZIO.fail(
                new RuntimeException(
                  s"UniFi POST /actions returned ${response.status.code}: $body")))
    yield ()
  end postAuthorize
end UnifiAuthorizationHandler
