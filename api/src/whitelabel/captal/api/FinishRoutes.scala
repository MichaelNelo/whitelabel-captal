package whitelabel.captal.api

import whitelabel.captal.api.wire.given
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

  val layer: ZLayer[SessionCookieConfig, Nothing, FinishRoutes] = ZLayer.fromFunction(
    FinishRoutes(_))

end FinishRoutes

/** `POST /api/finish`, on zio-http's `Endpoint`.
  *
  * The second route off tapir and the first one with a session, so this is where the plumbing
  * `secured` used to supply has to be said out loud: the cookie arrives as the whole `Cookie`
  * header and `SessionCookieConfig` picks this deployment's out of it, while the session lookup and
  * the phase rule stay in `SessionEndpoint.authorise`, which both front ends now call. Nothing
  * about *what* is allowed moves — only who extracts the cookie.
  *
  * `outError[ApiError](Status.BadRequest)` is what `errorOut(jsonBody[ApiError])` did implicitly: a
  * tapir error output carrying no status is a 400, which is what every test asserting a rejection
  * here has been reading all along.
  *
  * The constructor takes the cookie config rather than a `SessionEndpoint`, which it no longer has
  * any use for — that class exists to build tapir descriptions.
  */
final class FinishRoutes(cookieConfig: SessionCookieConfig):
  import FinishRoutes.*

  private val finish = Endpoint(Method.POST / "api" / "finish")
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
