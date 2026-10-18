package whitelabel.captal.api

import whitelabel.captal.api.wire.given
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.messages.{AdvertiserSurveyResponse, AnswerRequest, ApiError}
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Header, Method, Routes, Status}

object AdvertiserSurveyRoutes:

  type NextAdvertiserSurveyFlowType = Flow.Aux[
    Task,
    ProvideNextAdvertiserSurveyCommand,
    NextAdvertiserSurvey | NextStep]
  type AnswerAdvertiserFlowType = Flow.Aux[Task, AnswerAdvertiserCommand, NextStep]

  type FullEnv =
    SessionContext & SessionService & NextAdvertiserSurveyFlowType & AnswerAdvertiserFlowType

  val layer: ZLayer[SessionCookieConfig, Nothing, AdvertiserSurveyRoutes] = ZLayer.fromFunction(
    AdvertiserSurveyRoutes(_))

end AdvertiserSurveyRoutes

/** The one question asked about the advertiser's video, and the answer to it.
  *
  * Both start by reading `currentVideoId` off the session rather than from the request: the
  * question belongs to the video this session was shown, and a request cannot be allowed to name a
  * different one.
  */
final class AdvertiserSurveyRoutes(cookieConfig: SessionCookieConfig):
  import AdvertiserSurveyRoutes.*

  private def watching(cookie: Option[Header.Cookie]) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    Seq(Phase.AdvertiserVideoSurvey))

  /** Whatever a flow failed with, as the `ApiError` the route answers. See `VideoRoutes`. */
  private def asApiError[A](result: Task[A]): IO[ApiError, A] = result.catchAllCause: cause =>
    ApiErrors.failWith(
      cause.failureOrCause match
        case Left(error) =>
          error
        case Right(defect) =>
          new Exception(s"Defect: ${defect.prettyPrint}"))

  private val nextAdvertiserSurvey = Endpoint(Method.GET / "api" / "survey" / "advertiser" / "next")
    .header(HeaderCodec.cookie.optional)
    .out[AdvertiserSurveyResponse]
    .outError[ApiError](Status.BadRequest)

  private val answerAdvertiser = Endpoint(Method.POST / "api" / "survey" / "advertiser")
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[AdvertiserSurveyResponse]
    .outError[ApiError](Status.BadRequest)

  val routes: Routes[FullEnv, Nothing] = Routes(
    nextAdvertiserSurvey.implement: cookie =>
      for
        session <- watching(cookie)
        videoId <- ZIO
          .fromOption(session.currentVideoId)
          .orElseFail(ApiError.InternalError("No video ID in session"))
        flow     <- ZIO.service[NextAdvertiserSurveyFlowType]
        now      <- Clock.instant
        response <- asApiError(
          flow
            .execute(ProvideNextAdvertiserSurveyCommand(videoId, now))
            .map(AdvertiserSurveyResponse.from))
        // Update phase when transitioning away from AdvertiserVideoSurvey
        _ <-
          response match
            case AdvertiserSurveyResponse.Step(step) =>
              ZIO
                .serviceWithZIO[SessionService](_.setPhase(session.sessionId, step.phase))
                .mapError(ApiError.fromThrowable)
            case _ =>
              ZIO.unit
      yield response,
    answerAdvertiser.implement: (cookie, request) =>
      for
        session <- watching(cookie)
        _       <- ZIO
          .fromOption(session.currentVideoId)
          .orElseFail(ApiError.InternalError("No video ID in session"))
        answerFlow <- ZIO.service[AnswerAdvertiserFlowType]
        now        <- Clock.instant
        _          <- asApiError(
          answerFlow.execute(AnswerAdvertiserCommand(answer = request.answer, occurredAt = now)))
        // One question per video — go to Ready after answering
        _ <- ZIO
          .serviceWithZIO[SessionService](_.setPhase(session.sessionId, Phase.Ready))
          .mapError(ApiError.fromThrowable)
      yield AdvertiserSurveyResponse.Step(NextStep(Phase.Ready))
  )

end AdvertiserSurveyRoutes
