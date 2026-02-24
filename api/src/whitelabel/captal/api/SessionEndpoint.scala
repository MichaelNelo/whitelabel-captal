package whitelabel.captal.api

import izumi.reflect.Tag
import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.{Flow, Phase}
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.core.user.DeviceId
import whitelabel.captal.infra.{SessionContext, SessionService}
import zio.*

object SessionEndpoint:
  val sessionCookie = cookie[Option[String]]("session_id")

  enum OnMissing:
    case Fail
    case Create(deviceId: DeviceId, locale: String)

  private def resolveSessionData(
      cookie: Option[String],
      sessionService: SessionService,
      onMissing: OnMissing): IO[ApiError, SessionData] =
    cookie match
      case None =>
        onMissing match
          case OnMissing.Fail =>
            ZIO.fail(ApiError.SessionMissing)
          case OnMissing.Create(deviceId, locale) =>
            sessionService
              .create(deviceId, locale, Phase.IdentificationQuestion)
              .mapError(ApiError.InternalError(_))
      case Some(value) =>
        user.SessionId.fromString(value) match
          case None =>
            ZIO.fail(ApiError.SessionInvalid("Malformed session ID"))
          case Some(sessionId) =>
            sessionService
              .findById(sessionId)
              .mapError(ApiError.InternalError(_))
              .flatMap:
                case Some(data) =>
                  ZIO.succeed(data)
                case None =>
                  onMissing match
                    case OnMissing.Fail =>
                      ZIO.fail(ApiError.SessionExpired)
                    case OnMissing.Create(deviceId, locale) =>
                      sessionService
                        .create(deviceId, locale, Phase.IdentificationQuestion)
                        .mapError(ApiError.InternalError(_))

  def securedFlow[C: Tag, Res: Tag](onMissing: OnMissing = OnMissing.Fail)
      : ZPartialServerEndpoint[SessionContext & SessionService & Flow.Aux[Task, C, Res], Option[
        String], Flow.Aux[Task, C, Res], Unit, ApiError, Unit, Any] = endpoint
    .securityIn(sessionCookie)
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic: cookie =>
      (
        for
          _           <- ZIO.logTrace(s"securedFlow: cookie=$cookie")
          sessionData <- ZIO.serviceWithZIO[SessionService](
            resolveSessionData(cookie, _, onMissing))
          _    <- ZIO.logTrace(s"securedFlow: sessionData=$sessionData")
          _    <- SessionContext.set(sessionData)
          flow <- ZIO.service[Flow.Aux[Task, C, Res]]
          _    <- ZIO.logTrace("securedFlow: got flow")
        yield flow
      ).tapError(e => ZIO.logError(s"securedFlow error: $e"))

  def securedSessionData(onMissing: OnMissing = OnMissing.Fail)
      : ZPartialServerEndpoint[SessionContext & SessionService, Option[
        String], SessionData, Unit, ApiError, Unit, Any] = endpoint
    .securityIn(sessionCookie)
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic: cookie =>
      for
        sessionData <- ZIO.serviceWithZIO[SessionService](resolveSessionData(cookie, _, onMissing))
        _           <- SessionContext.set(sessionData)
      yield sessionData
end SessionEndpoint
