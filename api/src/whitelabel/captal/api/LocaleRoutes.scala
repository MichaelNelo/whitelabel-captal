package whitelabel.captal.api

import sttp.tapir.json.circe.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{endpoint, path, stringToPath}
import whitelabel.captal.api.schemas.given
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.messages.ApiError
import whitelabel.captal.messages.ApiError.given
import whitelabel.captal.messages.i18n.given
import whitelabel.captal.platform.services.LocaleService
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*

object LocaleRoutes:

  type FullEnv = SessionContext & SessionService & LocaleService

  val layer: ZLayer[SessionEndpoint & SessionCookieConfig, Nothing, LocaleRoutes] = ZLayer
    .fromFunction(LocaleRoutes(_, _))

end LocaleRoutes

final class LocaleRoutes(sessionEndpoint: SessionEndpoint, cookieConfig: SessionCookieConfig):
  import LocaleRoutes.*

  // ─── ListLocales ──────────────────────────────────────────────────────────

  // Declared here rather than in a shared module, which is where the other three in this file
  // already were. `LocaleEndpoints` held five of these and two were used; the module it lived in is
  // gone, and nothing was sharing the contract anyway — the client reads the response types, never
  // the endpoint descriptions.
  val listLocalesRoute: ZServerEndpoint[LocaleService, Any] = endpoint
    .get
    .in("api" / "locales")
    .out(jsonBody[List[String]])
    .errorOut(jsonBody[ApiError])
    .description("List available locales")
    .zServerLogic: _ =>
      LocaleService.listAvailable().mapError(ApiError.fromThrowable)

  // ─── SetLocale (sets the session cookie) ──────────────────────────────────

  import sttp.tapir.header
  import whitelabel.captal.messages.SetLocaleRequest.given
  import whitelabel.captal.messages.{SetLocaleRequest, StatusResponse}

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

  val getI18nRoute: ZServerEndpoint[LocaleService, Any] = endpoint
    .get
    .in("api" / "i18n" / path[String]("locale"))
    .out(jsonBody[I18n])
    .errorOut(jsonBody[ApiError])
    .description("Get i18n translations for a locale")
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
