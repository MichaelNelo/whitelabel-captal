package whitelabel.captal.api

import java.time.Instant

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.{SessionData, SurveyRepository}
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.endpoints.AdvertiserSurveyResponse.given
import whitelabel.captal.endpoints.AnswerRequest.given
import whitelabel.captal.endpoints.schemas.given
import whitelabel.captal.endpoints.{AdvertiserSurveyResponse, AnswerRequest, ApiError}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object AdvertiserSurveyRoutes:

  private def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.fromThrowable(other))

  type NextAdvertiserSurveyFlowType = Flow.Aux[
    Task,
    ProvideNextAdvertiserSurveyCommand,
    NextAdvertiserSurvey | NextStep]
  type AnswerAdvertiserFlowType = Flow.Aux[Task, AnswerAdvertiserCommand, NextStep]

  object NextAdvertiserSurvey:
    type Env = SessionContext & SessionService & NextAdvertiserSurveyFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.AdvertiserVideoSurvey))
      .get
      .in("api" / "survey" / "advertiser" / "next")
      .out(jsonBody[AdvertiserSurveyResponse])
      .serverLogic: session =>
        _ =>
          session.currentVideoId match
            case None =>
              ZIO.fail(ApiError.InternalError("No video ID in session"))
            case Some(videoId) =>
              for
                flow     <- ZIO.service[NextAdvertiserSurveyFlowType]
                response <- flow
                  .execute(ProvideNextAdvertiserSurveyCommand(videoId))
                  .map(AdvertiserSurveyResponse.from)
                  .catchAllCause: cause =>
                    val error =
                      cause.failureOrCause match
                        case Left(e)  => e
                        case Right(c) => new Exception(s"Defect: ${c.prettyPrint}")
                    toApiError(error).flatMap(ZIO.fail(_))
                // Update phase when transitioning away from AdvertiserVideoSurvey
                _ <- response match
                  case AdvertiserSurveyResponse.Step(step) =>
                    ZIO
                      .serviceWithZIO[SessionService](
                        _.setPhase(session.sessionId, step.phase))
                      .mapError(ApiError.fromThrowable)
                  case _ =>
                    ZIO.unit
              yield response
  end NextAdvertiserSurvey

  object AnswerAdvertiser:
    type Env = SessionContext & SessionService & AnswerAdvertiserFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.AdvertiserVideoSurvey))
      .post
      .in("api" / "survey" / "advertiser")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[AdvertiserSurveyResponse])
      .serverLogic: session =>
        request =>
          session.currentVideoId match
            case None =>
              ZIO.fail(ApiError.InternalError("No video ID in session"))
            case Some(videoId) =>
              for
                answerFlow <- ZIO.service[AnswerAdvertiserFlowType]
                cmd = AnswerAdvertiserCommand(answer = request.answer, occurredAt = Instant.now)
                _ <- answerFlow
                  .execute(cmd)
                  .catchAllCause: cause =>
                    val error =
                      cause.failureOrCause match
                        case Left(e)  => e
                        case Right(c) => new Exception(s"Defect: ${c.prettyPrint}")
                    toApiError(error).flatMap(ZIO.fail(_))
                // One question per video — go to Ready after answering
                _ <- ZIO
                  .serviceWithZIO[SessionService](
                    _.setPhase(session.sessionId, Phase.Ready))
                  .mapError(ApiError.fromThrowable)
              yield AdvertiserSurveyResponse.Step(NextStep(Phase.Ready))
  end AnswerAdvertiser

  type FullEnv =
    SessionContext & SessionService & NextAdvertiserSurveyFlowType & AnswerAdvertiserFlowType

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    NextAdvertiserSurvey.route.widen[FullEnv],
    AnswerAdvertiser.route.widen[FullEnv]
  )
end AdvertiserSurveyRoutes
