package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.messages.{
  ApiError,
  MarkVideoWatchedRequest,
  VideoResponse,
  VideoWatchedResponse
}
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes, Status}

object VideoRoutes:

  type NextVideoFlowType = Flow.Aux[Task, ProvideNextVideoCommand, NextVideo | NextStep]
  type MarkVideoWatchedFlowType = Flow.Aux[Task, MarkVideoWatchedCommand, NextStep]

  type FullEnv = SessionContext & SessionService & NextVideoFlowType & MarkVideoWatchedFlowType

  val layer: ZLayer[SessionCookieConfig, Nothing, VideoRoutes] = ZLayer.fromFunction(VideoRoutes(_))

end VideoRoutes

/** The advertiser video: ask for the next one, report it watched.
  *
  * Both are `Phase.AdvertiserVideo` only, and both answer a type the browser decodes — which made
  * `VideoResponse` the first shape this migration had to change rather than merely move. Its circe
  * pair flattened the payload under a `type` discriminator and a zio-schema `Schema` cannot
  * describe that; see the note on `VideoResponse` for what replaced it.
  */
final class VideoRoutes(cookieConfig: SessionCookieConfig):
  import VideoRoutes.*

  private def session(cookie: Option[zio.http.Header.Cookie]) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    Seq(Phase.AdvertiserVideo))

  /** Whatever a flow failed with, as the `ApiError` the route answers.
    *
    * The same `catchAllCause` both routes carried verbatim: a typed failure goes through
    * `ApiErrors`, and a defect is wrapped so its cause reaches the log rather than becoming a
    * response with no record of why.
    */
  private def asApiError[A](result: Task[A]): IO[ApiError, A] = result.catchAllCause: cause =>
    ApiErrors.failWith(
      cause.failureOrCause match
        case Left(error) =>
          error
        case Right(defect) =>
          new Exception(s"Defect: ${defect.prettyPrint}"))

  private val nextVideo = Endpoint(Method.GET / "api" / "video" / "next")
    .header(HeaderCodec.cookie.optional)
    .out[VideoResponse]
    .outError[ApiError](Status.BadRequest)

  private val markWatched = Endpoint(Method.POST / "api" / "video" / "watched")
    .header(HeaderCodec.cookie.optional)
    .in[MarkVideoWatchedRequest]
    .out[VideoWatchedResponse]
    .outError[ApiError](Status.BadRequest)

  val routes: Routes[FullEnv, Nothing] = Routes(
    nextVideo.implement: cookie =>
      for
        current  <- session(cookie)
        flow     <- ZIO.service[NextVideoFlowType]
        now      <- Clock.instant
        response <- asApiError(flow.execute(ProvideNextVideoCommand(now)).map(VideoResponse.from))
        // Update phase when no video is available
        _ <-
          response match
            case VideoResponse.Step(step) =>
              ZIO
                .serviceWithZIO[SessionService](_.setPhase(current.sessionId, step.phase))
                .mapError(ApiError.fromThrowable)
            case _ =>
              ZIO.unit
      yield response,
    markWatched.implement: (cookie, request) =>
      for
        _    <- session(cookie)
        flow <- ZIO.service[MarkVideoWatchedFlowType]
        now  <- Clock.instant
        command = MarkVideoWatchedCommand(
          durationWatched = request.durationWatched,
          completed = request.completed,
          occurredAt = now)
        result <- asApiError(
          flow
            .execute(command)
            .map((step: NextStep) => VideoWatchedResponse(Phase.toDbString(step.phase))))
      yield result
  )

end VideoRoutes
