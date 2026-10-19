package whitelabel.captal.api

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.messages.ApiError
import whitelabel.captal.platform.session.{CaptivePortalParams, SessionContext, SessionService}
import zio.*

/** How a request becomes a session, for every route that needs one. Takes the cookie value, not the
  * cookie config: the caller's own codec extracts it.
  */
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

  /** Resolve the session, check the phase, publish it into `SessionContext` — what every secured
    * route does first. An empty `allowedPhases` allows any phase; otherwise a mismatch fails with
    * `ApiError.WrongPhase`. The single copy of the phase rule.
    */
  def authorise(
      cookie: Option[String],
      onMissing: OnMissing,
      allowedPhases: Seq[Phase]): ZIO[SessionService & SessionContext, ApiError, SessionData] =
    for
      session <- resolveSession(cookie, onMissing)
      _       <-
        if allowedPhases.isEmpty || allowedPhases.contains(session.phase) then
          ZIO.unit
        else
          ZIO.fail(
            ApiError.WrongPhase(session.phase.toString, allowedPhases.map(_.toString).toList))
      _ <- SessionContext.set(session)
    yield session

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

end SessionEndpoint
