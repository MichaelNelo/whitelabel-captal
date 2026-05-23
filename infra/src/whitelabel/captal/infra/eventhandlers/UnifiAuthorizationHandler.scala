package whitelabel.captal.infra.eventhandlers

import io.circe.Json
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.infra.UnifiAccess
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig

/** Event handler that listens for [[whitelabel.captal.core.user.Event.UserFinishedProcess]] and
  * calls the UniFi Controller's `authorize-guest` endpoint to grant internet access to the
  * client's MAC for the configured duration.
  *
  * Lives outside the transactional event handler chain (composed via `.andThen` after the
  * transaction commits) so HTTP failures don't roll back DB writes.
  *
  * Behaviour:
  *   - If no [[UnifiAccess]] is configured for the location → logs and skips.
  *   - On UniFi 2xx → marks the session [[Phase.Authorized]] with `expiresAt = occurredAt +
  *     defaultDurationMinutes`.
  *   - On UniFi failure (timeout, non-2xx, etc.) → logs and skips. The session remains in
  *     [[Phase.Ready]]; the user can retry via `/api/finish` again.
  */
object UnifiAuthorizationHandler:

  /** HTTP client layer that trusts ALL TLS certs and (optionally) routes via an HTTP proxy.
    *
    * UniFi Controllers ship with self-signed certs by default and the connection to them goes via
    * a private overlay (Tailscale), so transport security is provided by the overlay rather than
    * the cert. In zio-http 3.x, `ClientSSLConfig.Default` is implemented with
    * `InsecureTrustManagerFactory.INSTANCE` — i.e. trust-all. We set `ssl = Some(Default)` to
    * force HTTPS connections through that path (when `ssl = None`, Netty uses the JDK default
    * trust store which rejects self-signed).
    *
    * The optional `proxyUrl` parameter is needed in production deploys where the API runs on ECS
    * Fargate and reaches the on-premise UCG via `tinyproxy (ECS daemon) → Tailscale subnet
    * router (VM) → LAN`. In local/test, leave it `None` for direct connection.
    */
  def trustAllClientLayer(proxyUrl: Option[String]): ZLayer[Any, Throwable, Client] =
    val configLayer: ZLayer[Any, Throwable, ZClient.Config] = ZLayer.fromZIO:
      val proxyOpt: ZIO[Any, Throwable, Option[Proxy]] = proxyUrl match
        case None =>
          ZIO.none
        case Some(raw) =>
          ZIO
            .fromEither(URL.decode(raw))
            .mapError(e => new RuntimeException(s"Invalid UNIFI_PROXY_URL '$raw': ${e.getMessage}"))
            .map(u => Some(Proxy(u)))
      proxyOpt.map: proxy =>
        ZClient.Config.default.copy(ssl = Some(ClientSSLConfig.Default), proxy = proxy)
    (configLayer ++
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++
      DnsResolver.default) >>> ZClient.live

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
                  session   <- ctx.getOrFail
                  expiresAt = occurredAt.plusSeconds(unifiCfg.defaultDurationMinutes * 60L)
                  result    <- callAuthorizeGuest(client, unifiCfg, session.clientMac).either
                  _ <-
                    result match
                      case Left(error) =>
                        ZIO.logError(
                          s"UniFi authorize failed for session ${session.sessionId.asString}: ${error.getMessage}")
                      case Right(_) =>
                        sessionService.setAuthorized(session.sessionId, expiresAt) *>
                          ZIO.logInfo(
                            s"UniFi authorized session ${session.sessionId.asString} until $expiresAt")
                yield ()

  private def callAuthorizeGuest(
      client: Client,
      unifi: UnifiAccess,
      clientMac: String): Task[Unit] =
    val basePath =
      if unifi.unifiOs then
        "proxy/network/api"
      else
        "api"
    val urlStr = s"https://${unifi.host}:${unifi.port}/$basePath/s/${unifi.site}/cmd/stamgr"
    val macNormalized = clientMac.toLowerCase.replace("-", ":")
    val bodyJson = Json
      .obj(
        "cmd"     -> Json.fromString("authorize-guest"),
        "mac"     -> Json.fromString(macNormalized),
        "minutes" -> Json.fromInt(unifi.defaultDurationMinutes))
      .noSpaces

    for
      url <- ZIO
        .fromEither(URL.decode(urlStr))
        .mapError(e => new RuntimeException(s"Invalid UniFi URL '$urlStr': ${e.getMessage}"))
      request = Request
        .post(url, Body.fromString(bodyJson))
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader("X-API-KEY", unifi.apiToken)
      response <- client.batched(request)
      _ <-
        if response.status.code < 200 || response.status.code >= 300 then
          response
            .body
            .asString
            .flatMap: body =>
              ZIO.fail(new RuntimeException(s"UniFi returned ${response.status.code}: $body"))
        else
          ZIO.unit
    yield ()
end UnifiAuthorizationHandler
