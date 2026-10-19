package whitelabel.captal.platform

import io.circe.{Decoder, Json, parser as circeParser}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig

/** Thin client over the UniFi Network Integration v1 API.
  *
  * Both operations share the same two-step flow, differing only in the action posted:
  *   1. `GET /proxy/network/integration/v1/sites/{siteId}/clients?filter=macAddress.eq('aa:bb:..')`
  *      → extract `clientId` from the response (single match expected for an active client).
  *   2. `POST /proxy/network/integration/v1/sites/{siteId}/clients/{clientId}/actions` with body
  *      `{"action": <ACTION>, ...}`.
  */
trait UnifiClient:
  def authorizeGuest(unifi: UnifiAccess, clientMac: String): Task[Unit]
  def revokeGuestAccess(unifi: UnifiAccess, clientMac: String): Task[Unit]

object UnifiClient:

  /** The only two client actions the Integration v1 API admits, and the only two places the
    * literals are spelled. There is no separate DISCONNECT action: `UNAUTHORIZE_GUEST_ACCESS`
    * already disconnects the guest client.
    */
  val AuthorizeAction: String = "AUTHORIZE_GUEST_ACCESS"
  val RevokeAction: String = "UNAUTHORIZE_GUEST_ACCESS"

  /** Bounds how long a single UniFi operation can block its caller. */
  private val RequestTimeout: Duration = 10.seconds

  /** HTTP client layer that trusts ALL TLS certs and (optionally) routes via an HTTP proxy.
    *
    * In zio-http 3.x `ClientSSLConfig.Default` is trust-all, which is what accepts the controller's
    * self-signed cert; with `ssl = None` Netty uses the JDK trust store and rejects it.
    *
    * @param proxyUrl
    *   HTTP proxy to reach the UCG through (the Tailscale proxy in deployed environments); `None`
    *   connects directly.
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

  /** Integration v1 root for a location's controller. IPv6 hosts (e.g. a Tailscale ULA
    * `fd7a:115c:...`, detected by the colon) are bracketed per RFC 3986.
    */
  def baseUrl(unifi: UnifiAccess, scheme: String = "https"): String =
    val hostFmt =
      if unifi.host.contains(":") then
        s"[${unifi.host}]"
      else
        unifi.host
    s"$scheme://$hostFmt:${unifi.port}/proxy/network/integration/v1"

  /** UniFi normalizes MACs to lowercase with colons; the filter is an exact string match. */
  def normalizeMac(mac: String): String = mac.toLowerCase.replace("-", ":")

  /** Decode the JSON envelope `{"data": [{"id": "...", ...}, ...]}` and return the first id. */
  def extractClientId(body: String, macLower: String): Either[String, String] =
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

  def live(client: Client, scheme: String = "https"): UnifiClient =
    new UnifiClient:
      def authorizeGuest(unifi: UnifiAccess, clientMac: String): Task[Unit] = run(
        unifi,
        clientMac,
        AuthorizeAction,
        Some(unifi.defaultDurationMinutes))

      def revokeGuestAccess(unifi: UnifiAccess, clientMac: String): Task[Unit] = run(
        unifi,
        clientMac,
        RevokeAction,
        None)

      private def run(
          unifi: UnifiAccess,
          clientMac: String,
          action: String,
          timeLimitMinutes: Option[Int]): Task[Unit] =
        val base = baseUrl(unifi, scheme)
        val macNormalized = normalizeMac(clientMac)
        val op =
          for
            clientId <- findClientId(client, base, unifi.siteId, unifi.apiToken, macNormalized)
            _        <- postAction(
              client,
              base,
              unifi.siteId,
              clientId,
              unifi.apiToken,
              action,
              timeLimitMinutes)
          yield ()
        op.timeoutFail(new RuntimeException(s"UniFi $action timed out after $RequestTimeout"))(
          RequestTimeout)
      end run

  /** GET /sites/{siteId}/clients?filter=macAddress.eq('mac') — returns a paginated list; exactly
    * one match is expected for a connected guest.
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

  /** POST /sites/{siteId}/clients/{clientId}/actions. The body is the `action` discriminator plus
    * whatever that action accepts: authorize an optional `timeLimitMinutes`, unauthorize nothing.
    */
  private def postAction(
      client: Client,
      base: String,
      siteId: String,
      clientId: String,
      apiToken: String,
      action: String,
      timeLimitMinutes: Option[Int]): Task[Unit] =
    val urlStr = s"$base/sites/$siteId/clients/$clientId/actions"
    val fields =
      ("action" -> Json.fromString(action)) ::
        timeLimitMinutes.map(m => "timeLimitMinutes" -> Json.fromInt(m)).toList
    val bodyJson = Json.obj(fields*).noSpaces
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
                  s"UniFi POST /actions ($action) returned ${response.status.code}: $body")))
    yield ()
  end postAction

  val layer: ZLayer[Client, Nothing, UnifiClient] = ZLayer.fromFunction((client: Client) =>
    live(client))

  /** No-op for tests, which must never reach a real controller. Explicitly typed so
    * `ZLayer.succeed(UnifiClient.noop)` picks up the `UnifiClient` tag.
    */
  val noop: UnifiClient =
    new UnifiClient:
      def authorizeGuest(unifi: UnifiAccess, clientMac: String): Task[Unit] = ZIO.logDebug(
        s"UnifiClient.noop: skipping authorize for $clientMac")

      def revokeGuestAccess(unifi: UnifiAccess, clientMac: String): Task[Unit] = ZIO.logDebug(
        s"UnifiClient.noop: skipping revoke for $clientMac")
end UnifiClient
