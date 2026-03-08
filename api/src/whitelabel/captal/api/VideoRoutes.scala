package whitelabel.captal.api

import java.time.Instant

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.endpoints.MarkVideoWatchedRequest.given
import whitelabel.captal.endpoints.VideoResponse.given
import whitelabel.captal.endpoints.VideoWatchedResponse.given
import whitelabel.captal.endpoints.schemas.given
import whitelabel.captal.endpoints.{ApiError, MarkVideoWatchedRequest, VideoResponse, VideoWatchedResponse}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object VideoRoutes:

  private def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.fromThrowable(other))

  type NextVideoFlowType = Flow.Aux[Task, ProvideNextVideoCommand.type, NextVideo | NextStep]
  type MarkVideoWatchedFlowType = Flow.Aux[Task, MarkVideoWatchedCommand, NextStep]

  object NextVideo:
    type Env = SessionContext & SessionService & NextVideoFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.AdvertiserVideo))
      .get
      .in("api" / "video" / "next")
      .out(jsonBody[VideoResponse])
      .serverLogic: session =>
        _ =>
          for
            flow   <- ZIO.service[NextVideoFlowType]
            result <- flow
              .execute(ProvideNextVideoCommand)
              .map(VideoResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result
  end NextVideo

  object MarkWatched:
    type Env = SessionContext & SessionService & MarkVideoWatchedFlowType

    val route: ZServerEndpoint[Env, Any] = SessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.AdvertiserVideo))
      .post
      .in("api" / "video" / "watched")
      .in(jsonBody[MarkVideoWatchedRequest])
      .out(jsonBody[VideoWatchedResponse])
      .serverLogic: session =>
        request =>
          for
            flow <- ZIO.service[MarkVideoWatchedFlowType]
            cmd = MarkVideoWatchedCommand(
              durationWatched = request.durationWatched,
              completed = request.completed,
              occurredAt = Instant.now)
            result <- flow
              .execute(cmd)
              .map((step: NextStep) => VideoWatchedResponse(step.phase.toString))
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result
  end MarkWatched

  type FullEnv = SessionContext & SessionService & NextVideoFlowType & MarkVideoWatchedFlowType

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    NextVideo.route.widen[FullEnv],
    MarkWatched.route.widen[FullEnv]
  )
end VideoRoutes
