package whitelabel.captal.endpoints

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*
import whitelabel.captal.core.application.Phase.given
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.application.commands.NextIdentificationSurvey.given
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.endpoints.AnswerRequest.given
import whitelabel.captal.endpoints.SetLocaleRequest.given
import whitelabel.captal.endpoints.StatusResponse.given
import whitelabel.captal.endpoints.schemas.given

object SurveyEndpoints:
  // Common security input
  val sessionCookie: EndpointInput[Option[String]] = cookie[Option[String]]("session_id")

  // ─────────────────────────────────────────────────────────────────────────────
  // Answer Endpoints - all use AnswerRequest with AnswerValue
  // ─────────────────────────────────────────────────────────────────────────────

  val answerEmail: PublicEndpoint[
    (Option[String], AnswerRequest),
    ApiError,
    Unit,
    Any] = endpoint
    .post
    .in("api" / "survey" / "email")
    .in(sessionCookie)
    .in(jsonBody[AnswerRequest])
    .out(emptyOutput)
    .errorOut(jsonBody[ApiError])
    .description("Answer the email identification question")

  val answerProfiling: PublicEndpoint[
    (Option[String], AnswerRequest),
    ApiError,
    Unit,
    Any] = endpoint
    .post
    .in("api" / "survey" / "profiling")
    .in(sessionCookie)
    .in(jsonBody[AnswerRequest])
    .out(emptyOutput)
    .errorOut(jsonBody[ApiError])
    .description("Answer a profiling survey question")

  val answerLocation: PublicEndpoint[
    (Option[String], AnswerRequest),
    ApiError,
    Unit,
    Any] = endpoint
    .post
    .in("api" / "survey" / "location")
    .in(sessionCookie)
    .in(jsonBody[AnswerRequest])
    .out(emptyOutput)
    .errorOut(jsonBody[ApiError])
    .description("Answer a location survey question")

  // ─────────────────────────────────────────────────────────────────────────────
  // Query Endpoints
  // ─────────────────────────────────────────────────────────────────────────────

  val nextSurvey: PublicEndpoint[
    Option[String],
    ApiError,
    Option[NextIdentificationSurvey],
    Any] = endpoint
    .get
    .in("api" / "survey" / "next")
    .in(sessionCookie)
    .out(jsonBody[Option[NextIdentificationSurvey]])
    .errorOut(jsonBody[ApiError])
    .description("Get the next identification survey for the user")

  val status: PublicEndpoint[
    Option[String],
    ApiError,
    StatusResponse,
    Any] = endpoint
    .get
    .in("api" / "status")
    .in(sessionCookie)
    .out(jsonBody[StatusResponse])
    .errorOut(jsonBody[ApiError])
    .description("Get the current phase/status - returns error if no session")

  // ─────────────────────────────────────────────────────────────────────────────
  // Session/Locale Endpoints
  // ─────────────────────────────────────────────────────────────────────────────

  val listLocales: PublicEndpoint[
    Unit,
    ApiError,
    List[String],
    Any] = endpoint
    .get
    .in("api" / "locales")
    .out(jsonBody[List[String]])
    .errorOut(jsonBody[ApiError])
    .description("List available locales")

  val setLocale: PublicEndpoint[
    (Option[String], Option[String], SetLocaleRequest),
    ApiError,
    CookieValueWithMeta,
    Any] = endpoint
    .put
    .in("api" / "session" / "locale")
    .in(sessionCookie)
    .in(header[Option[String]]("Accept-Language"))
    .in(jsonBody[SetLocaleRequest])
    .out(setCookie("session_id"))
    .errorOut(jsonBody[ApiError])
    .description("Set locale - creates session if needed")
end SurveyEndpoints
