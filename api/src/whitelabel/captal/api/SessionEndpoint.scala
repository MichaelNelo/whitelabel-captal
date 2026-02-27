package whitelabel.captal.api

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.core.user.DeviceId
import whitelabel.captal.endpoints.ApiError
import whitelabel.captal.infra.SessionService
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
              .serviceWithZIO[SessionService](
                _.create(deviceId, locale, Phase.IdentificationQuestion))
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
                        .serviceWithZIO[SessionService](
                          _.create(deviceId, locale, Phase.IdentificationQuestion))
                        .mapError(ApiError.fromThrowable)
end SessionEndpoint
