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

    val route: ZServerEndpoint[Env, Any] = LocaleEndpoints
      .setLocale
      .zServerLogic: (cookie, _, request) =>
        val locale = request.locale
        for
          sessionData <- SessionEndpoint.resolveSession(
            cookie,
            SessionEndpoint
              .OnMissing
              .Create(whitelabel.captal.core.user.DeviceId("unknown"), locale))
          updatedData <-
            if sessionData.locale != locale then
              ZIO
                .serviceWithZIO[SessionService](_.setLocale(sessionData.sessionId, locale))
                .mapError(ApiError.fromThrowable)
                .as(sessionData.copy(locale = locale))
            else
              ZIO.succeed(sessionData)
          _ <- SessionContext.set(updatedData)
        yield (
          Some(CookieValueWithMeta.unsafeApply(updatedData.sessionId.asString, path = Some("/"))),
          whitelabel.captal.endpoints.StatusResponse(updatedData.phase, updatedData.locale))
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

    val route: ZServerEndpoint[Env, Any] = LocaleEndpoints
      .resetPhase
      .zServerLogic: cookie =>
        for
          sessionData <- SessionEndpoint.resolveSession(cookie, SessionEndpoint.OnMissing.Fail)
          _           <- ZIO
            .serviceWithZIO[SessionService](
              _.setPhase(sessionData.sessionId, whitelabel.captal.core.application.Phase.Welcome))
            .mapError(ApiError.fromThrowable)
          _ <- SessionContext.set(
            sessionData.copy(phase = whitelabel.captal.core.application.Phase.Welcome))
        yield whitelabel
          .captal
          .endpoints
          .StatusResponse(whitelabel.captal.core.application.Phase.Welcome, sessionData.locale)
  end ResetPhase

  type FullEnv = SessionContext & SessionService & LocaleService

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    ListLocales.route.widen[FullEnv],
    SetLocale.route.widen[FullEnv],
    GetI18n.route.widen[FullEnv])

  // Dev-only routes
  def devRoutes: List[ZServerEndpoint[FullEnv, Any]] = List(ResetPhase.route.widen[FullEnv])
end LocaleRoutes
