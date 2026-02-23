package whitelabel.captal.api

import java.time.Instant

import sttp.tapir.ztapir.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.commands.AnswerEmailCommand
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.user.{DeviceId, SessionId}
import zio.*

object SurveyRoutes:

  object AnswerEmail:
    val route: ZServerEndpoint[SurveyEndpoints.AnswerEmail.Env, Any] =
      SurveyEndpoints.AnswerEmail.endpoint.serverLogic(handler =>
        request =>
          for
            cmd      <- ZIO.succeed(buildCommand(request))
            opResult <- handler.handle(cmd).mapError(e => ApiError.InternalError(e.getMessage))
            _        <- ZIO.fromEither(Op.run(opResult)).mapError(ApiError.fromAppErrors)
          yield AnswerEmailResponse(success = true))

    private def buildCommand(request: AnswerEmailRequest): AnswerEmailCommand =
      AnswerEmailCommand(
        sessionId = SessionId.generate,
        deviceId = DeviceId("unknown"),
        locale = "en",
        answer = AnswerValue.Text(request.email),
        occurredAt = Instant.now)

  def routes: List[ZServerEndpoint[SurveyEndpoints.AnswerEmail.Env, Any]] =
    List(AnswerEmail.route)
