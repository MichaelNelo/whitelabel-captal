package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.Handler
import whitelabel.captal.core.infrastructure.{Session, SessionData, SessionRepository}
import whitelabel.captal.core.{survey, user}
import zio.*

object SessionEndpoint:
  val sessionCookie = cookie[Option[String]]("session_id")

  private def validateSession(
      cookie: Option[String],
      sessionRepo: SessionRepository[Task]): IO[ApiError, Session[Task]] =
    cookie match
      case None =>
        ZIO.fail(ApiError.SessionMissing)
      case Some(value) =>
        user.SessionId.fromString(value) match
          case None =>
            ZIO.fail(ApiError.SessionInvalid("Malformed session ID"))
          case Some(sessionId) =>
            sessionRepo
              .findById(sessionId)
              .mapError(e => ApiError.InternalError(e.getMessage))
              .flatMap:
                case None =>
                  ZIO.fail(ApiError.SessionExpired)
                case Some(data) =>
                  ZIO.succeed(makeSession(data, sessionRepo))

  private def makeSession(data: SessionData, repo: SessionRepository[Task]): Session[Task] =
    new Session[Task]:
      def getSessionData: Task[SessionData] = ZIO.from(data)
      def setCurrentSurvey(surveyId: survey.Id, questionId: survey.question.Id): Task[Unit] = repo
        .setCurrentSurvey(data.sessionId, surveyId, questionId)

  def securedHandler[R, C, Res](
      handlerEffect: Session[Task] => ZIO[R, Nothing, Handler.Aux[Task, C, Res]])
      : ZPartialServerEndpoint[SessionRepository[Task] & R, Option[String], Handler.Aux[
        Task,
        C,
        Res], Unit, ApiError, Unit, Any] = endpoint
    .securityIn(sessionCookie)
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic { cookie =>
      for
        session <- ZIO.serviceWithZIO[SessionRepository[Task]](validateSession(cookie, _))
        handler <- handlerEffect(session)
      yield handler
    }
end SessionEndpoint
