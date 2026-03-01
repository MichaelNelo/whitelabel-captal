package whitelabel.captal.endpoints

import sttp.tapir.*
import sttp.tapir.json.circe.*
import whitelabel.captal.endpoints.AnswerRequest.given
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.endpoints.StatusResponse.given
import whitelabel.captal.endpoints.SurveyResponse.given
import whitelabel.captal.endpoints.schemas.given

object SurveyEndpoints:
  // Common security input
  val sessionCookie: EndpointInput[Option[String]] = cookie[Option[String]]("session_id")

  // ─────────────────────────────────────────────────────────────────────────────
  // Answer Endpoints - all use AnswerRequest with AnswerValue
  // Return SurveyResponse: either next survey or next step (terminal phase)
  // ─────────────────────────────────────────────────────────────────────────────

  val answerEmail: PublicEndpoint[(Option[String], AnswerRequest), ApiError, SurveyResponse, Any] =
    endpoint
      .post
      .in("api" / "survey" / "email")
      .in(sessionCookie)
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .errorOut(jsonBody[ApiError])
      .description("Answer the email identification question")

  val answerProfiling
      : PublicEndpoint[(Option[String], AnswerRequest), ApiError, SurveyResponse, Any] = endpoint
    .post
    .in("api" / "survey" / "profiling")
    .in(sessionCookie)
    .in(jsonBody[AnswerRequest])
    .out(jsonBody[SurveyResponse])
    .errorOut(jsonBody[ApiError])
    .description("Answer a profiling survey question")

  val answerLocation
      : PublicEndpoint[(Option[String], AnswerRequest), ApiError, SurveyResponse, Any] = endpoint
    .post
    .in("api" / "survey" / "location")
    .in(sessionCookie)
    .in(jsonBody[AnswerRequest])
    .out(jsonBody[SurveyResponse])
    .errorOut(jsonBody[ApiError])
    .description("Answer a location survey question")

  // ─────────────────────────────────────────────────────────────────────────────
  // Query Endpoints
  // ─────────────────────────────────────────────────────────────────────────────

  val nextSurvey: PublicEndpoint[Option[String], ApiError, SurveyResponse, Any] = endpoint
    .get
    .in("api" / "survey" / "next")
    .in(sessionCookie)
    .out(jsonBody[SurveyResponse])
    .errorOut(jsonBody[ApiError])
    .description("Get the next identification survey for the user")

  val status: PublicEndpoint[Option[String], ApiError, StatusResponse, Any] = endpoint
    .get
    .in("api" / "status")
    .in(sessionCookie)
    .out(jsonBody[StatusResponse])
    .errorOut(jsonBody[ApiError])
    .description("Get the current phase/status - returns error if no session")

end SurveyEndpoints
