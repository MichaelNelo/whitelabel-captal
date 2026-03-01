package whitelabel.captal.api

import java.time.Instant

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.endpoints.AnswerRequest.given
import whitelabel.captal.endpoints.StatusResponse.given
import whitelabel.captal.endpoints.SurveyResponse.given
import whitelabel.captal.endpoints.schemas.given
import whitelabel.captal.endpoints.{AnswerRequest, ApiError, StatusResponse, SurveyResponse}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object SurveyRoutes:

  private def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.fromThrowable(other))

  type AnswerEmailFlowType = Flow.Aux[Task, AnswerEmailCommand, NextStep]
  type AnswerProfilingFlowType = Flow.Aux[Task, AnswerProfilingCommand, NextStep]
  type AnswerLocationFlowType = Flow.Aux[Task, AnswerLocationCommand, NextStep]
  type NextSurveyFlowType = Flow.Aux[
    Task,
    ProvideNextIdentificationSurveyCommand.type,
    NextIdentificationSurvey | NextStep]

  object AnswerEmail:
    type Env = SessionContext & SessionService & AnswerEmailFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .withPhase(Phase.IdentificationQuestion)
      .post
      .in("api" / "survey" / "email")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .serverLogic(session =>
        request => handleAnswer(session, request, AnswerEmailCommand(request.answer, Instant.now)))

    private def handleAnswer(
        session: SessionData,
        @annotation.unused request: AnswerRequest,
        cmd: AnswerEmailCommand) =
      for
        _          <- SessionContext.set(session)
        answerFlow <- ZIO.service[AnswerEmailFlowType]
        result     <- answerFlow
          .execute(cmd)
          .map(SurveyResponse.from)
          .catchAllCause: cause =>
            val error =
              cause.failureOrCause match
                case Left(e) =>
                  e
                case Right(c) =>
                  new Exception(s"Defect: ${c.prettyPrint}")
            toApiError(error).flatMap(ZIO.fail(_))
      yield result
  end AnswerEmail

  object AnswerProfiling:
    type Env = SessionContext & SessionService & AnswerProfilingFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .withPhase(Phase.IdentificationQuestion)
      .post
      .in("api" / "survey" / "profiling")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .serverLogic: session =>
        request =>
          for
            _          <- SessionContext.set(session)
            answerFlow <- ZIO.service[AnswerProfilingFlowType]
            cmd = AnswerProfilingCommand(answer = request.answer, occurredAt = Instant.now)
            result <- answerFlow
              .execute(cmd)
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result
  end AnswerProfiling

  object AnswerLocation:
    type Env = SessionContext & SessionService & AnswerLocationFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .withPhase(Phase.IdentificationQuestion)
      .post
      .in("api" / "survey" / "location")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .serverLogic: session =>
        request =>
          for
            _          <- SessionContext.set(session)
            answerFlow <- ZIO.service[AnswerLocationFlowType]
            cmd = AnswerLocationCommand(answer = request.answer, occurredAt = Instant.now)
            result <- answerFlow
              .execute(cmd)
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result
  end AnswerLocation

  object NextSurvey:
    type Env = SessionContext & SessionService & NextSurveyFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .withPhase(Phase.Welcome, Phase.IdentificationQuestion)
      .get
      .in("api" / "survey" / "next")
      .out(jsonBody[SurveyResponse])
      .serverLogic: session =>
        _ =>
          for
            _ <- SessionContext.set(session)
            // Transition from Welcome to IdentificationQuestion when user requests next survey
            _ <-
              if session.phase == Phase.Welcome then
                ZIO
                  .serviceWithZIO[SessionService](
                    _.setPhase(session.sessionId, Phase.IdentificationQuestion))
                  .mapError(ApiError.fromThrowable)
              else
                ZIO.unit
            flow   <- ZIO.service[NextSurveyFlowType]
            result <- flow
              .execute(ProvideNextIdentificationSurveyCommand)
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result
  end NextSurvey

  object Status:
    type Env = SessionContext & SessionService

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .secured
      .get
      .in("api" / "status")
      .out(jsonBody[StatusResponse])
      .serverLogic: session =>
        _ => SessionContext.set(session).as(StatusResponse(session.phase, session.locale))

  type FullEnv =
    SessionContext & SessionService & AnswerEmailFlowType & AnswerProfilingFlowType &
      AnswerLocationFlowType & NextSurveyFlowType

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    AnswerEmail.route.widen[FullEnv],
    AnswerProfiling.route.widen[FullEnv],
    AnswerLocation.route.widen[FullEnv],
    NextSurvey.route.widen[FullEnv],
    Status.route.widen[FullEnv]
  )
end SurveyRoutes
