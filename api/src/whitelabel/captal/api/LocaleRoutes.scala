package whitelabel.captal.api

import sttp.tapir.ztapir.*
import whitelabel.captal.endpoints.{ApiError, LocaleEndpoints}
import whitelabel.captal.infra.services.LocaleService
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object LocaleRoutes:

  type FullEnv = SessionContext & SessionService & LocaleService

  val layer: ZLayer[SessionEndpoint & SessionCookieConfig, Nothing, LocaleRoutes] = ZLayer
    .fromFunction(LocaleRoutes(_, _))

end LocaleRoutes

final class LocaleRoutes(sessionEndpoint: SessionEndpoint, cookieConfig: SessionCookieConfig):
  import LocaleRoutes.*

  // ─── ListLocales ──────────────────────────────────────────────────────────

  val listLocalesRoute: ZServerEndpoint[LocaleService, Any] = LocaleEndpoints
    .listLocales
    .zServerLogic: _ =>
      LocaleService.listAvailable().mapError(ApiError.fromThrowable)

  // ─── SetLocale (sets the session cookie) ──────────────────────────────────

  import sttp.tapir.json.circe.*
  import sttp.tapir.header
  import whitelabel.captal.endpoints.SetLocaleRequest.given
  import whitelabel.captal.endpoints.{SetLocaleRequest, StatusResponse}

  val setLocaleRoute: ZServerEndpoint[SessionContext & SessionService, Any] = sessionEndpoint
    .secured()
    .put
    .in("api" / "session" / "locale")
    .in(header[Option[String]]("Accept-Language"))
    .in(jsonBody[SetLocaleRequest])
    .out(cookieConfig.tapirOutput.and(jsonBody[StatusResponse]))
    .serverLogic: session =>
      (_, request) =>
        val locale = request.locale
        for updatedData <-
            if session.locale != locale then
              ZIO
                .serviceWithZIO[SessionService](_.setLocale(session.sessionId, locale))
                .mapError(ApiError.fromThrowable)
                .as(session.copy(locale = locale))
            else
              ZIO.succeed(session)
        yield (
          Some(cookieConfig.asMeta(updatedData.sessionId.asString)),
          StatusResponse(updatedData.phase, updatedData.locale))

  // ─── GetI18n ──────────────────────────────────────────────────────────────

  val getI18nRoute: ZServerEndpoint[LocaleService, Any] = LocaleEndpoints
    .getI18n
    .zServerLogic: locale =>
      LocaleService.getI18n(locale).mapError(ApiError.fromThrowable)

  // ─── ResetPhase (dev only) ────────────────────────────────────────────────

  val resetPhaseRoute: ZServerEndpoint[SessionContext & SessionService, Any] =
    import whitelabel.captal.core.application.Phase
    sessionEndpoint
      .secured()
      .post
      .in("api" / "dev" / "reset-phase")
      .out(jsonBody[StatusResponse])
      .serverLogic: session =>
        _ =>
          ZIO
            .serviceWithZIO[SessionService](_.setPhase(session.sessionId, Phase.Welcome))
            .mapError(ApiError.fromThrowable)
            .as(StatusResponse(Phase.Welcome, session.locale))

  // ─── Aggregate ────────────────────────────────────────────────────────────

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    listLocalesRoute.widen[FullEnv],
    setLocaleRoute.widen[FullEnv],
    getI18nRoute.widen[FullEnv])

  def devRoutes: List[ZServerEndpoint[FullEnv, Any]] = List(resetPhaseRoute.widen[FullEnv])

end LocaleRoutes
