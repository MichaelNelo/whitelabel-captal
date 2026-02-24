package whitelabel.captal.api

import java.time.Instant

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.survey.question.{AnswerValue, OptionId}
import whitelabel.captal.infra.{SessionContext, SessionService}
import zio.*

object SurveyRoutes:

  private def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.InternalError(other))

  object AnswerEmail:
    val route: ZServerEndpoint[SurveyEndpoints.AnswerEmail.Env, Any] = SurveyEndpoints
      .AnswerEmail
      .endpoint
      .serverLogic: flow =>
        request =>
          val cmd = AnswerEmailCommand(
            answer = AnswerValue.Text(request.email),
            occurredAt = Instant.now)
          flow
            .execute(cmd)
            .as(AnswerEmailResponse(success = true))
            .catchAllCause: cause =>
              val error =
                cause.failureOrCause match
                  case Left(e) =>
                    e
                  case Right(c) =>
                    new Exception(s"Defect: ${c.prettyPrint}")
              ZIO.logError(s"AnswerEmail: cause=${cause.prettyPrint}") *>
                toApiError(error).flatMap(ZIO.fail(_))
  end AnswerEmail

  object AnswerProfiling:
    val route: ZServerEndpoint[SurveyEndpoints.AnswerProfiling.Env, Any] = SurveyEndpoints
      .AnswerProfiling
      .endpoint
      .serverLogic: flow =>
        request =>
          val cmd = AnswerProfilingCommand(
            answer = AnswerValue.SingleChoice(OptionId.unsafe(request.optionId)),
            occurredAt = Instant.now)
          flow
            .execute(cmd)
            .as(AnswerProfilingResponse(success = true))
            .catchAllCause: cause =>
              val error =
                cause.failureOrCause match
                  case Left(e) =>
                    e
                  case Right(c) =>
                    new Exception(s"Defect: ${c.prettyPrint}")
              ZIO.logError(s"AnswerProfiling: cause=${cause.prettyPrint}") *>
                toApiError(error).flatMap(ZIO.fail(_))
  end AnswerProfiling

  object AnswerLocation:
    val route: ZServerEndpoint[SurveyEndpoints.AnswerLocation.Env, Any] = SurveyEndpoints
      .AnswerLocation
      .endpoint
      .serverLogic: flow =>
        request =>
          val cmd = AnswerLocationCommand(
            answer = AnswerValue.SingleChoice(OptionId.unsafe(request.optionId)),
            occurredAt = Instant.now)
          flow
            .execute(cmd)
            .as(AnswerLocationResponse(success = true))
            .catchAllCause: cause =>
              val error =
                cause.failureOrCause match
                  case Left(e) =>
                    e
                  case Right(c) =>
                    new Exception(s"Defect: ${c.prettyPrint}")
              ZIO.logError(s"AnswerLocation: cause=${cause.prettyPrint}") *>
                toApiError(error).flatMap(ZIO.fail(_))
  end AnswerLocation

  object NextIdentificationSurvey:
    val route: ZServerEndpoint[SurveyEndpoints.NextIdentificationSurvey.Env, Any] = SurveyEndpoints
      .NextIdentificationSurvey
      .endpoint
      .serverLogic: flow =>
        _ =>
          ZIO.logTrace("NextIdentificationSurvey: starting execute") *>
            flow
              .execute(ProvideNextIdentificationSurveyCommand)
              .tap(r => ZIO.logTrace(s"NextIdentificationSurvey: result=$r"))
              .map(_.map(NextSurveyResponse.from))
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                ZIO.logError(s"NextIdentificationSurvey: cause=${cause.prettyPrint}") *>
                  toApiError(error).flatMap(ZIO.fail(_))
  end NextIdentificationSurvey

  object Status:
    val route: ZServerEndpoint[SurveyEndpoints.Status.Env, Any] = SurveyEndpoints
      .Status
      .endpoint
      .serverLogic: sessionData =>
        _ =>
          val response = StatusResponse(PhaseResponse.from(sessionData.phase))
          val cookie = CookieValueWithMeta.unsafeApply(sessionData.sessionId.asString)
          ZIO.succeed((response, cookie))

  type FullEnv =
    SessionContext & SessionService & SurveyEndpoints.AnswerEmail.FlowType &
      SurveyEndpoints.AnswerProfiling.FlowType & SurveyEndpoints.AnswerLocation.FlowType &
      SurveyEndpoints.NextIdentificationSurvey.FlowType

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    AnswerEmail.route.widen[FullEnv],
    AnswerProfiling.route.widen[FullEnv],
    AnswerLocation.route.widen[FullEnv],
    NextIdentificationSurvey.route.widen[FullEnv],
    Status.route.widen[FullEnv])
end SurveyRoutes
