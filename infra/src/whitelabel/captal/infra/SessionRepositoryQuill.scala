package whitelabel.captal.infra

import io.getquill.*
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.jdbczio.Quill
import izumi.reflect.Tag
import whitelabel.captal.core.infrastructure.{SessionData, SessionRepository}
import whitelabel.captal.core.{survey, user}
import zio.*

object SessionRepositoryQuill:
  inline def findByIdQuery = quote: (sessionId: String) =>
    query[SessionRow].filter(_.id == sessionId)

  inline def updateCurrentSurveyQuery = quote:
    (sessionId: String, surveyId: String, questionId: String) =>
      query[SessionRow]
        .filter(_.id == sessionId)
        .update(_.currentSurveyId -> Some(surveyId), _.currentQuestionId -> Some(questionId))

  def apply[D <: SqlIdiom, N <: NamingStrategy](quill: Quill[D, N]): SessionRepository[Task] =
    new SessionRepository[Task]:
      import quill.*

      def findById(sessionId: user.SessionId): Task[Option[SessionData]] =
        run(findByIdQuery(lift(sessionId.asString))).map(_.headOption.flatMap(toSessionData)).orDie

      def setCurrentSurvey(
          sessionId: user.SessionId,
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Unit] =
        run(
          updateCurrentSurveyQuery(
            lift(sessionId.asString),
            lift(surveyId.asString),
            lift(questionId.asString))).unit.orDie

  private def toSessionData(row: SessionRow): Option[SessionData] =
    for
      sessionId <- user.SessionId.fromString(row.id)
      userId    <- user.Id.fromString(row.userId)
    yield SessionData(
      sessionId,
      userId,
      row.locale,
      row.currentSurveyId.flatMap(survey.Id.fromString),
      row.currentQuestionId.flatMap(survey.question.Id.fromString))

  def layer[D <: SqlIdiom: Tag, N <: NamingStrategy: Tag]
      : ZLayer[Quill[D, N], Nothing, SessionRepository[Task]] = ZLayer.fromFunction(apply[D, N])
end SessionRepositoryQuill
