package whitelabel.captal.api

import java.time.Instant

import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.AnswerEmailCommand
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.user.{DeviceId, SessionId}
import zio.*

object SurveyRoutes:

  object AnswerEmail:
    val route: ZServerEndpoint[SurveyEndpoints.AnswerEmail.Env, Any] =
      SurveyEndpoints.AnswerEmail.endpoint.serverLogic(flow =>
        request =>
          for
            cmd <- ZIO.succeed(buildCommand(request))
            _   <- flow.execute(cmd).mapError(toApiError)
          yield AnswerEmailResponse(success = true))

    private def buildCommand(request: AnswerEmailRequest): AnswerEmailCommand =
      AnswerEmailCommand(
        sessionId = SessionId.generate,
        deviceId = DeviceId("unknown"),
        locale = "en",
        answer = AnswerValue.Text(request.email),
        occurredAt = Instant.now)

    private def toApiError(error: Throwable): ApiError =
      error match
        case Flow.HandlerError(errors) => ApiError.fromAppErrors(errors)
        case other                     => ApiError.InternalError(other.getMessage)

  def routes: List[ZServerEndpoint[SurveyEndpoints.AnswerEmail.Env, Any]] =
    List(AnswerEmail.route)
