package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, Phase}
import whitelabel.captal.messages.{ApiError, StatusResponse}
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes, Status}

object FinishRoutes:

  type FinishFlowType = Flow.Aux[Task, FinishCommand, Unit]

  type FullEnv = SessionContext & SessionService & FinishFlowType

  val layer: ZLayer[SessionCookieConfig & ApiBase, Nothing, FinishRoutes] = ZLayer.fromFunction(
    FinishRoutes(_, _))

end FinishRoutes

/** `POST /api/finish`: runs the finish flow for a session in `Phase.Ready` and answers that
  * session's refreshed status. Rejections answer 400 with an `ApiError` body.
  */
final class FinishRoutes(cookieConfig: SessionCookieConfig, base: ApiBase):
  import FinishRoutes.*

  private val finish = Endpoint(base(Method.POST / "api" / "finish"))
    .header(HeaderCodec.cookie.optional)
    .out[StatusResponse]
    .outError[ApiError](Status.BadRequest)

  val routes: Routes[FullEnv, Nothing] = Routes(
    finish.implement: cookie =>
      for
        session <- SessionEndpoint.authorise(
          cookieConfig.valueFrom(cookie),
          SessionEndpoint.OnMissing.Fail,
          Seq(Phase.Ready))
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
        // Post-commit: UnifiAccessHandler already ran here. Re-read the
        // session to surface phase + accessExpiresAt (Some(...) iff UniFi succeeded).
        fresh <- ZIO
          .serviceWithZIO[SessionService](_.findById(session.sessionId))
          .mapError(ApiError.fromThrowable)
          .someOrFail(ApiError.SessionMissing)
      yield StatusResponse(
        fresh.phase,
        fresh.locale,
        Option(fresh.redirectUrl).filter(_.nonEmpty),
        StatusResponse.secondsRemainingFrom(fresh.accessExpiresAt, now)))

end FinishRoutes
