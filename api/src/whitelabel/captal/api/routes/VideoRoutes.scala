package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.WireCheck.implementReadable
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.video.PlaybackId
import whitelabel.captal.messages.{
  ApiError,
  EndPlaybackRequest,
  MarkVideoWatchedRequest,
  PlaybackResponse,
  StartPlaybackRequest,
  VideoResponse,
  VideoWatchedResponse
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes, Status}

object VideoRoutes:

  type NextVideoFlowType = Flow.Aux[Task, ProvideNextVideoCommand, NextVideo | NextStep]
  type MarkVideoWatchedFlowType = Flow.Aux[Task, MarkVideoWatchedCommand, NextStep]
  type StartPlaybackFlowType = Flow.Aux[Task, StartPlaybackCommand, Unit]
  type EndPlaybackFlowType = Flow.Aux[Task, EndPlaybackCommand, Unit]

  type FullEnv =
    SessionContext & SessionService & QuillSqlite & NextVideoFlowType & MarkVideoWatchedFlowType &
      StartPlaybackFlowType & EndPlaybackFlowType

  val layer: ZLayer[SessionCookieConfig & ApiBase, Nothing, VideoRoutes] = ZLayer.fromFunction(
    VideoRoutes(_, _))

end VideoRoutes

/** The advertiser video: ask for the next one, start and end playing its file, report it watched.
  * All `Phase.AdvertiserVideo` only, except ending a playback, which a page leaving sends whatever
  * phase the session has reached.
  */
final class VideoRoutes(cookieConfig: SessionCookieConfig, base: ApiBase):
  import VideoRoutes.*

  private def session(
      cookie: Option[zio.http.Header.Cookie],
      phases: Seq[Phase] = Seq(Phase.AdvertiserVideo)) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    phases)

  private def playbackId(value: String): IO[ApiError, PlaybackId] = ZIO
    .fromOption(PlaybackId.fromString(value))
    .orElseFail(ApiError.InvalidPlaybackId(value))

  /** Whatever a flow failed with, as the `ApiError` the route answers: a typed failure goes through
    * `ApiErrors`, and a defect is wrapped so its cause still reaches the log.
    */
  private def asApiError[A](result: Task[A]): ZIO[QuillSqlite & SessionContext, ApiError, A] =
    result.catchAllCause: cause =>
      ApiErrors.failWith(
        cause.failureOrCause match
          case Left(error) =>
            error
          case Right(defect) =>
            new Exception(s"Defect: ${defect.prettyPrint}"))

  private val nextVideo = Endpoint(base(Method.GET / "api" / "video" / "next"))
    .header(HeaderCodec.cookie.optional)
    .out[VideoResponse]
    .outError[ApiError](Status.BadRequest)

  private val markWatched = Endpoint(base(Method.POST / "api" / "video" / "watched"))
    .header(HeaderCodec.cookie.optional)
    .in[MarkVideoWatchedRequest]
    .out[VideoWatchedResponse]
    .outError[ApiError](Status.BadRequest)

  private val startPlayback = Endpoint(base(Method.POST / "api" / "video" / "playback" / "start"))
    .header(HeaderCodec.cookie.optional)
    .in[StartPlaybackRequest]
    .out[PlaybackResponse]
    .outError[ApiError](Status.BadRequest)

  private val endPlayback = Endpoint(base(Method.POST / "api" / "video" / "playback" / "end"))
    .header(HeaderCodec.cookie.optional)
    .in[EndPlaybackRequest]
    .out[PlaybackResponse]
    .outError[ApiError](Status.BadRequest)

  val routes: Routes[FullEnv, Nothing] = Routes(
    nextVideo.implementReadable: cookie =>
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
    markWatched.implementReadable: (cookie, request) =>
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
      yield result,
    startPlayback.implementReadable: (cookie, request) =>
      for
        _    <- session(cookie)
        id   <- playbackId(request.playbackId)
        flow <- ZIO.service[StartPlaybackFlowType]
        now  <- Clock.instant
        _    <- asApiError(flow.execute(StartPlaybackCommand(id, request.videoUrl, now)))
      yield PlaybackResponse(request.playbackId),
    endPlayback.implementReadable: (cookie, request) =>
      for
        _    <- session(cookie, phases = Seq.empty)
        id   <- playbackId(request.playbackId)
        flow <- ZIO.service[EndPlaybackFlowType]
        now  <- Clock.instant
        _    <- asApiError(flow.execute(EndPlaybackCommand(id, now)))
      yield PlaybackResponse(request.playbackId)
  )

end VideoRoutes
