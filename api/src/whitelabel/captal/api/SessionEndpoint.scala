package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.core.user.DeviceId
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.endpoints.{ApiError, SurveyEndpoints}
import whitelabel.captal.infra.session.SessionService
import zio.*

object SessionEndpoint:
  enum OnMissing:
    case Fail
    case Create(deviceId: DeviceId, locale: String)

  def resolveSession(
      cookie: Option[String],
      onMissing: OnMissing): ZIO[SessionService, ApiError, SessionData] =
    cookie match
      case None =>
        onMissing match
          case OnMissing.Fail =>
            ZIO.fail(ApiError.SessionMissing)
          case OnMissing.Create(deviceId, locale) =>
            ZIO
              .serviceWithZIO[SessionService](_.create(deviceId, locale, Phase.Welcome))
              .mapError(ApiError.fromThrowable)
      case Some(value) =>
        user.SessionId.fromString(value) match
          case None =>
            ZIO.fail(ApiError.SessionInvalid("Malformed session ID"))
          case Some(sessionId) =>
            ZIO
              .serviceWithZIO[SessionService](_.findById(sessionId))
              .mapError(ApiError.fromThrowable)
              .flatMap:
                case Some(data) =>
                  ZIO.succeed(data)
                case None =>
                  onMissing match
                    case OnMissing.Fail =>
                      ZIO.fail(ApiError.SessionExpired)
                    case OnMissing.Create(deviceId, locale) =>
                      ZIO
                        .serviceWithZIO[SessionService](_.create(deviceId, locale, Phase.Welcome))
                        .mapError(ApiError.fromThrowable)

  // Secured endpoint: session only (any phase)
  val secured: ZPartialServerEndpoint[SessionService, Option[
    String], SessionData, Unit, ApiError, Unit, Any] = endpoint
    .securityIn(SurveyEndpoints.sessionCookie)
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic(cookie => resolveSession(cookie, OnMissing.Fail))

  // Secured endpoint with phase validation
  def withPhase(phases: Phase*): ZPartialServerEndpoint[SessionService, Option[
    String], SessionData, Unit, ApiError, Unit, Any] = endpoint
    .securityIn(SurveyEndpoints.sessionCookie)
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic: cookie =>
      for
        session <- resolveSession(cookie, OnMissing.Fail)
        _       <-
          if phases.contains(session.phase) then
            ZIO.unit
          else
            ZIO.fail(ApiError.WrongPhase(session.phase.toString, phases.map(_.toString).toList))
      yield session

end SessionEndpoint
