package whitelabel.captal.api

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.ztapir.*
import whitelabel.captal.endpoints.{ApiError, LocaleEndpoints}
import whitelabel.captal.infra.services.LocaleService
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object LocaleRoutes:

  object ListLocales:
    type Env = LocaleService

    val route: ZServerEndpoint[Env, Any] = LocaleEndpoints
      .listLocales
      .zServerLogic: _ =>
        LocaleService.listAvailable().mapError(ApiError.fromThrowable)

  object SetLocale:
    type Env = SessionContext & SessionService

    import sttp.tapir.json.circe.*
    import sttp.tapir.{header, setCookieOpt}
    import whitelabel.captal.endpoints.{SetLocaleRequest, StatusResponse, SurveyEndpoints}
    import whitelabel.captal.endpoints.SetLocaleRequest.given

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .secured()
      .put
      .in("api" / "session" / "locale")
      .in(header[Option[String]]("Accept-Language"))
      .in(jsonBody[SetLocaleRequest])
      .out(setCookieOpt(SurveyEndpoints.sessionCookieName).and(jsonBody[StatusResponse]))
      .serverLogic: session =>
        (_, request) =>
          val locale = request.locale
          for
            updatedData <-
              if session.locale != locale then
                ZIO
                  .serviceWithZIO[SessionService](_.setLocale(session.sessionId, locale))
                  .mapError(ApiError.fromThrowable)
                  .as(session.copy(locale = locale))
              else
                ZIO.succeed(session)
          yield (
            Some(CookieValueWithMeta.unsafeApply(updatedData.sessionId.asString, path = Some("/"))),
            StatusResponse(updatedData.phase, updatedData.locale))
  end SetLocale

  object GetI18n:
    type Env = LocaleService

    val route: ZServerEndpoint[Env, Any] = LocaleEndpoints
      .getI18n
      .zServerLogic: locale =>
        LocaleService.getI18n(locale).mapError(ApiError.fromThrowable)
  end GetI18n

  object ResetPhase:
    type Env = SessionContext & SessionService

    import sttp.tapir.json.circe.*
    import whitelabel.captal.core.application.Phase
    import whitelabel.captal.endpoints.StatusResponse

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
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
  end ResetPhase

  type FullEnv = SessionContext & SessionService & LocaleService

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    ListLocales.route.widen[FullEnv],
    SetLocale.route.widen[FullEnv],
    GetI18n.route.widen[FullEnv])

  // Dev-only routes
  def devRoutes: List[ZServerEndpoint[FullEnv, Any]] = List(ResetPhase.route.widen[FullEnv])
end LocaleRoutes
