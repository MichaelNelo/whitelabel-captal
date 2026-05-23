package whitelabel.captal.endpoints

import sttp.tapir.*
import sttp.tapir.json.circe.*
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.endpoints.StatusResponse.given

object FinishEndpoints:
  val finish: PublicEndpoint[Option[String], ApiError, StatusResponse, Any] = endpoint
    .post
    .in("api" / "finish")
    .in(SurveyEndpoints.sessionCookie)
    .out(jsonBody[StatusResponse])
    .errorOut(jsonBody[ApiError])
    .description(
      "Trigger UniFi authorization for the current session; returns post-commit status (phase + accessExpiresAt).")
end FinishEndpoints
