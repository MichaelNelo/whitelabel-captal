package whitelabel.captal.endpoints

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.endpoints.SetLocaleRequest.given
import whitelabel.captal.endpoints.i18n.given

object LocaleEndpoints:
  val sessionCookie: EndpointInput[Option[String]] = cookie[Option[String]]("session_id")

  val listLocales: PublicEndpoint[Unit, ApiError, List[String], Any] = endpoint
    .get
    .in("api" / "locales")
    .out(jsonBody[List[String]])
    .errorOut(jsonBody[ApiError])
    .description("List available locales")

  val setLocale: PublicEndpoint[
    (Option[String], Option[String], SetLocaleRequest),
    ApiError,
    (Option[CookieValueWithMeta], StatusResponse),
    Any] = endpoint
    .put
    .in("api" / "session" / "locale")
    .in(sessionCookie)
    .in(header[Option[String]]("Accept-Language"))
    .in(jsonBody[SetLocaleRequest])
    .out(setCookieOpt("session_id").and(jsonBody[StatusResponse]))
    .errorOut(jsonBody[ApiError])
    .description("Set locale - creates session if needed")

  val getI18n: PublicEndpoint[String, ApiError, I18n, Any] = endpoint
    .get
    .in("api" / "i18n" / path[String]("locale"))
    .out(jsonBody[I18n])
    .errorOut(jsonBody[ApiError])
    .description("Get i18n translations for a locale")

  // Dev-only endpoint to reset session phase to Welcome
  val resetPhase: PublicEndpoint[Option[String], ApiError, StatusResponse, Any] = endpoint
    .post
    .in("api" / "dev" / "reset-phase")
    .in(sessionCookie)
    .out(jsonBody[StatusResponse])
    .errorOut(jsonBody[ApiError])
    .description("DEV ONLY: Reset session phase to Welcome")
end LocaleEndpoints
