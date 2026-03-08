package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.endpoints.{ApiError, SurveyEndpoints}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object SessionEndpoint:
  enum OnMissing:
    case Fail
    case Create(userAgent: String, locale: String)

  def resolveSession(
      cookie: Option[String],
      onMissing: OnMissing): ZIO[SessionService, ApiError, SessionData] =
    cookie match
      case None =>
        onMissing match
          case OnMissing.Fail =>
            ZIO.fail(ApiError.SessionMissing)
          case OnMissing.Create(userAgent, locale) =>
            ZIO
              .serviceWithZIO[SessionService](_.create(userAgent, locale, Phase.Welcome))
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
                    case OnMissing.Create(userAgent, locale) =>
                      ZIO
                        .serviceWithZIO[SessionService](_.create(userAgent, locale, Phase.Welcome))
                        .mapError(ApiError.fromThrowable)

  // Secured endpoint with optional phase validation
  def secured(
      onMissingSession: OnMissing = OnMissing.Fail,
      allowedPhases: Seq[Phase] = Seq.empty
  ): ZPartialServerEndpoint[
    SessionService & SessionContext,
    Option[String],
    SessionData,
    Unit,
    ApiError,
    Unit,
    Any] =
    endpoint
      .securityIn(SurveyEndpoints.sessionCookie)
      .errorOut(jsonBody[ApiError])
      .zServerSecurityLogic: cookie =>
        for
          session <- resolveSession(cookie, onMissingSession)
          _       <-
            if allowedPhases.isEmpty || allowedPhases.contains(session.phase) then
              ZIO.unit
            else
              ZIO.fail(ApiError.WrongPhase(session.phase.toString, allowedPhases.map(_.toString).toList))
          _ <- SessionContext.set(session)
        yield session

end SessionEndpoint
