package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.endpoints.ApiError
import whitelabel.captal.endpoints.ApiError.given
import whitelabel.captal.infra.session.{CaptivePortalParams, SessionContext, SessionService}
import zio.*

final class SessionEndpoint(cookieConfig: SessionCookieConfig):

  def secured(
      onMissingSession: SessionEndpoint.OnMissing = SessionEndpoint.OnMissing.Fail,
      allowedPhases: Seq[Phase] = Seq.empty)
      : ZPartialServerEndpoint[SessionService & SessionContext, Option[
        String], SessionData, Unit, ApiError, Unit, Any] = endpoint
    .securityIn(cookieConfig.tapirInput)
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic: cookie =>
      for
        session <- SessionEndpoint.resolveSession(cookie, onMissingSession)
        _       <-
          if allowedPhases.isEmpty || allowedPhases.contains(session.phase) then
            ZIO.unit
          else
            ZIO.fail(
              ApiError.WrongPhase(session.phase.toString, allowedPhases.map(_.toString).toList))
        _ <- SessionContext.set(session)
      yield session

end SessionEndpoint

object SessionEndpoint:
  enum OnMissing:
    case Fail
    case Create(userAgent: String, locale: String, portalParams: Option[CaptivePortalParams] = None)

    /** Like Create but pre-populates `userId` from the cross-location `captal_user` cookie. */
    case CreateForUser(
        userAgent: String,
        locale: String,
        portalParams: Option[CaptivePortalParams],
        userId: user.Id)

  private def createSession(
      userAgent: String,
      locale: String,
      portalParams: Option[CaptivePortalParams]): ZIO[SessionService, ApiError, SessionData] =
    portalParams match
      case Some(params) =>
        ZIO
          .serviceWithZIO[SessionService](_.create(userAgent, locale, Phase.Welcome, params))
          .mapError(ApiError.fromThrowable)
      case None =>
        ZIO.fail(ApiError.SessionMissing)

  private def createSessionForUser(
      userAgent: String,
      locale: String,
      portalParams: Option[CaptivePortalParams],
      userId: user.Id): ZIO[SessionService, ApiError, SessionData] =
    portalParams match
      case Some(params) =>
        ZIO
          .serviceWithZIO[SessionService](
            _.createForUser(userAgent, locale, Phase.Welcome, params, userId))
          .mapError(ApiError.fromThrowable)
      case None =>
        ZIO.fail(ApiError.SessionMissing)

  private def fromOnMissing(onMissing: OnMissing): ZIO[SessionService, ApiError, SessionData] =
    onMissing match
      case OnMissing.Fail =>
        ZIO.fail(ApiError.SessionMissing)
      case OnMissing.Create(userAgent, locale, portalParams) =>
        createSession(userAgent, locale, portalParams)
      case OnMissing.CreateForUser(userAgent, locale, portalParams, userId) =>
        createSessionForUser(userAgent, locale, portalParams, userId)

  def resolveSession(
      cookie: Option[String],
      onMissing: OnMissing): ZIO[SessionService, ApiError, SessionData] =
    cookie match
      case None =>
        fromOnMissing(onMissing)
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
                    case _ =>
                      fromOnMissing(onMissing)

  val layer: ZLayer[SessionCookieConfig, Nothing, SessionEndpoint] = ZLayer.fromFunction(
    SessionEndpoint(_))

end SessionEndpoint
