package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, Phase}
import whitelabel.captal.endpoints.StatusResponse.given
import whitelabel.captal.endpoints.{ApiError, StatusResponse}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object FinishRoutes:

  type FinishFlowType = Flow.Aux[Task, FinishCommand, Unit]

  type FullEnv = SessionContext & SessionService & FinishFlowType

  val layer: ZLayer[SessionEndpoint, Nothing, FinishRoutes] = ZLayer.fromFunction(FinishRoutes(_))

end FinishRoutes

final class FinishRoutes(sessionEndpoint: SessionEndpoint):
  import FinishRoutes.*

  val finishRoute: ZServerEndpoint[SessionContext & SessionService & FinishFlowType, Any] =
    sessionEndpoint
      .secured(onMissingSession = SessionEndpoint.OnMissing.Fail, allowedPhases = Seq(Phase.Ready))
      .post
      .in("api" / "finish")
      .out(jsonBody[StatusResponse])
      .serverLogic: session =>
        _ =>
          for
            flow <- ZIO.service[FinishFlowType]
            now  <- Clock.instant
            _    <- flow
              .execute(FinishCommand(now))
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                ApiErrors.failWith(error)
            // Post-commit: UnifiAuthorizationHandler already ran here. Re-read the
            // session to surface phase + accessExpiresAt (Some(...) iff UniFi succeeded).
            fresh <- ZIO
              .serviceWithZIO[SessionService](_.findById(session.sessionId))
              .mapError(ApiError.fromThrowable)
              .someOrFail(ApiError.SessionMissing)
          yield StatusResponse(fresh.phase, fresh.locale, fresh.accessExpiresAt)

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(finishRoute.widen[FullEnv])

end FinishRoutes
