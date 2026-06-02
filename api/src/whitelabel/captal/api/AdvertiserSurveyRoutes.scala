package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.endpoints.AdvertiserSurveyResponse.given
import whitelabel.captal.endpoints.AnswerRequest.given
import whitelabel.captal.endpoints.{AdvertiserSurveyResponse, AnswerRequest, ApiError}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object AdvertiserSurveyRoutes:

  type NextAdvertiserSurveyFlowType = Flow.Aux[
    Task,
    ProvideNextAdvertiserSurveyCommand,
    NextAdvertiserSurvey | NextStep]
  type AnswerAdvertiserFlowType = Flow.Aux[Task, AnswerAdvertiserCommand, NextStep]

  type FullEnv =
    SessionContext & SessionService & NextAdvertiserSurveyFlowType & AnswerAdvertiserFlowType

  val layer: ZLayer[SessionEndpoint, Nothing, AdvertiserSurveyRoutes] = ZLayer.fromFunction(
    AdvertiserSurveyRoutes(_))

end AdvertiserSurveyRoutes

final class AdvertiserSurveyRoutes(sessionEndpoint: SessionEndpoint):
  import AdvertiserSurveyRoutes.*

  val nextAdvertiserSurveyRoute
      : ZServerEndpoint[SessionContext & SessionService & NextAdvertiserSurveyFlowType, Any] =
    sessionEndpoint
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
                now      <- Clock.instant
                response <- flow
                  .execute(ProvideNextAdvertiserSurveyCommand(videoId, now))
                  .map(AdvertiserSurveyResponse.from)
                  .catchAllCause: cause =>
                    val error =
                      cause.failureOrCause match
                        case Left(e) =>
                          e
                        case Right(c) =>
                          new Exception(s"Defect: ${c.prettyPrint}")
                    ApiErrors.failWith(error)
                // Update phase when transitioning away from AdvertiserVideoSurvey
                _ <-
                  response match
                    case AdvertiserSurveyResponse.Step(step) =>
                      ZIO
                        .serviceWithZIO[SessionService](_.setPhase(session.sessionId, step.phase))
                        .mapError(ApiError.fromThrowable)
                    case _ =>
                      ZIO.unit
              yield response

  val answerAdvertiserRoute
      : ZServerEndpoint[SessionContext & SessionService & AnswerAdvertiserFlowType, Any] =
    sessionEndpoint
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
                now <- Clock.instant
                cmd = AnswerAdvertiserCommand(answer = request.answer, occurredAt = now)
                _ <- answerFlow
                  .execute(cmd)
                  .catchAllCause: cause =>
                    val error =
                      cause.failureOrCause match
                        case Left(e) =>
                          e
                        case Right(c) =>
                          new Exception(s"Defect: ${c.prettyPrint}")
                    ApiErrors.failWith(error)
                // One question per video — go to Ready after answering
                _ <- ZIO
                  .serviceWithZIO[SessionService](_.setPhase(session.sessionId, Phase.Ready))
                  .mapError(ApiError.fromThrowable)
              yield AdvertiserSurveyResponse.Step(NextStep(Phase.Ready))

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    nextAdvertiserSurveyRoute.widen[FullEnv],
    answerAdvertiserRoute.widen[FullEnv])

end AdvertiserSurveyRoutes
