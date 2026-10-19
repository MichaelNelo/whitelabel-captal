package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiBase, SessionEndpoint}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.messages.{ApiError, SetLocaleRequest, StatusResponse}
import whitelabel.captal.platform.services.LocaleService
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.{HeaderCodec, PathCodec}
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes, Status}

object LocaleRoutes:

  type FullEnv = SessionContext & SessionService & LocaleService

  val layer: ZLayer[SessionCookieConfig & ApiBase, Nothing, LocaleRoutes] = ZLayer.fromFunction(
    LocaleRoutes(_, _))

end LocaleRoutes

/** The locale list, the translation bundle, and the route that changes a session's language.
  *
  * `PUT /api/session/locale` writes the session cookie back through `asSetCookie`, with the same
  * name and path every other route that sets it uses — two cookies differing in path would be two
  * entries in the browser rather than one.
  */
final class LocaleRoutes(cookieConfig: SessionCookieConfig, base: ApiBase):
  import LocaleRoutes.*

  private def session(cookie: Option[zio.http.Header.Cookie]) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    Seq.empty)

  // ─── ListLocales ──────────────────────────────────────────────────────────

  private val listLocales = Endpoint(base(Method.GET / "api" / "locales"))
    .out[List[String]]
    .outError[ApiError](Status.BadRequest)

  // ─── SetLocale (sets the session cookie) ──────────────────────────────────

  // The locale comes from the request body; `Accept-Language` is deliberately not an input here.
  private val setLocale = Endpoint(base(Method.PUT / "api" / "session" / "locale"))
    .header(HeaderCodec.cookie.optional)
    .in[SetLocaleRequest]
    .out[StatusResponse]
    .outHeader(HeaderCodec.setCookie)
    .outError[ApiError](Status.BadRequest)

  // ─── GetI18n ──────────────────────────────────────────────────────────────

  private val getI18n = Endpoint(base(Method.GET / "api" / "i18n" / PathCodec.string("locale")))
    .out[I18n]
    .outError[ApiError](Status.BadRequest)

  // ─── ResetPhase (dev only) ────────────────────────────────────────────────

  private val resetPhase = Endpoint(base(Method.POST / "api" / "dev" / "reset-phase"))
    .header(HeaderCodec.cookie.optional)
    .out[StatusResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── Aggregate ────────────────────────────────────────────────────────────

  val routes: Routes[FullEnv, Nothing] = Routes(
    listLocales.implement(_ => LocaleService.listAvailable().mapError(ApiError.fromThrowable)),
    getI18n.implement(locale => LocaleService.getI18n(locale).mapError(ApiError.fromThrowable)),
    setLocale.implement: (cookie, request) =>
      for
        current <- session(cookie)
        updated <-
          if current.locale != request.locale then
            ZIO
              .serviceWithZIO[SessionService](_.setLocale(current.sessionId, request.locale))
              .mapError(ApiError.fromThrowable)
              .as(current.copy(locale = request.locale))
          else
            ZIO.succeed(current)
      yield (
        StatusResponse(updated.phase, updated.locale),
        cookieConfig.asSetCookie(updated.sessionId.asString))
  )

  val devRoutes: Routes[FullEnv, Nothing] = Routes(
    resetPhase.implement: cookie =>
      for
        current <- session(cookie)
        _       <- ZIO
          .serviceWithZIO[SessionService](_.setPhase(current.sessionId, Phase.Welcome))
          .mapError(ApiError.fromThrowable)
      yield StatusResponse(Phase.Welcome, current.locale))

end LocaleRoutes
