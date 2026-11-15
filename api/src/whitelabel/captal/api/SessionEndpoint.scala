package whitelabel.captal.api

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.messages.ApiError
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*

/** How a request becomes a session, for every route that needs one. Takes the cookie value, not the
  * cookie config: the caller's own codec extracts it. Only entering the portal starts a session,
  * through its command; every other route needs one already.
  */
object SessionEndpoint:
  enum OnMissing:
    case Fail

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
        ZIO.fail(ApiError.SessionMissing)
      case Some(value) =>
        user.SessionId.fromString(value) match
          case None =>
            ZIO.fail(ApiError.SessionInvalid("Malformed session ID"))
          case Some(sessionId) =>
            ZIO
              .serviceWithZIO[SessionService](_.findById(sessionId))
              .mapError(ApiError.fromThrowable)
              .someOrFail(ApiError.SessionExpired)

end SessionEndpoint
