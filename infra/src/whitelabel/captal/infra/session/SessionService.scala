package whitelabel.captal.infra.session

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.SessionRow
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

trait SessionService:
  def findById(sessionId: user.SessionId): Task[Option[SessionData]]
  def create(deviceId: user.DeviceId, locale: String, phase: Phase): Task[SessionData]
  def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit]
  def setLocale(sessionId: user.SessionId, locale: String): Task[Unit]
  def setCurrentQuestion(sessionId: user.SessionId, question: FullyQualifiedQuestionId): Task[Unit]
  def clearCurrentQuestion(sessionId: user.SessionId): Task[Unit]

object SessionService:
  inline def findByIdQuery = quote: (sessionIdParam: user.SessionId) =>
    query[SessionRow].filter(_.id == sessionIdParam)

  inline def updateCurrentSurveyQuery = quote:
    (
        sessionIdParam: user.SessionId,
        surveyIdParam: survey.Id,
        questionIdParam: survey.question.Id) =>
      query[SessionRow]
        .filter(_.id == sessionIdParam)
        .update(
          _.currentSurveyId   -> Some(surveyIdParam),
          _.currentQuestionId -> Some(questionIdParam))

  inline def clearCurrentSurveyQuery = quote: (sessionIdParam: user.SessionId) =>
    query[SessionRow]
      .filter(_.id == sessionIdParam)
      .update(_.currentSurveyId -> None, _.currentQuestionId -> None)

  inline def updatePhaseQuery = quote: (sessionIdParam: user.SessionId, phaseParam: Phase) =>
    query[SessionRow].filter(_.id == sessionIdParam).update(_.phase -> phaseParam)

  inline def updateLocaleQuery = quote: (sessionIdParam: user.SessionId, localeParam: String) =>
    query[SessionRow].filter(_.id == sessionIdParam).update(_.locale -> localeParam)

  def apply(quill: QuillSqlite): SessionService =
    new SessionService:
      import quill.*

      def findById(sessionId: user.SessionId): Task[Option[SessionData]] =
        run(findByIdQuery(lift(sessionId))).map(_.headOption.map(toSessionData)).orDie

      def create(deviceId: user.DeviceId, locale: String, phase: Phase): Task[SessionData] =
        val sessionId = user.SessionId.generate
        val now = java.time.Instant.now.toString
        val row = SessionRow(
          id = sessionId,
          userId = None,
          deviceId = deviceId,
          locale = locale,
          phase = phase,
          currentSurveyId = None,
          currentQuestionId = None,
          createdAt = now)
        run(
          query[SessionRow].insert(
            _.id                -> lift(row.id),
            _.userId            -> lift(row.userId),
            _.deviceId          -> lift(row.deviceId),
            _.locale            -> lift(row.locale),
            _.phase             -> lift(row.phase),
            _.currentSurveyId   -> lift(row.currentSurveyId),
            _.currentQuestionId -> lift(row.currentQuestionId),
            _.createdAt         -> lift(row.createdAt)
          )).orDie *> ZIO.succeed(SessionData(sessionId, None, locale, phase, None))
      end create

      def setCurrentQuestion(sessionId: user.SessionId, question: FullyQualifiedQuestionId): Task[Unit] =
        run(updateCurrentSurveyQuery(lift(sessionId), lift(question.surveyId), lift(question.questionId))).unit.orDie

      def clearCurrentQuestion(sessionId: user.SessionId): Task[Unit] =
        run(clearCurrentSurveyQuery(lift(sessionId))).unit.orDie

      def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit] =
        run(updatePhaseQuery(lift(sessionId), lift(phase))).unit.orDie

      def setLocale(sessionId: user.SessionId, locale: String): Task[Unit] =
        run(updateLocaleQuery(lift(sessionId), lift(locale))).unit.orDie

  private def toSessionData(row: SessionRow): SessionData =
    val currentQuestion = (row.currentSurveyId, row.currentQuestionId) match
      case (Some(surveyId), Some(questionId)) => Some(FullyQualifiedQuestionId(surveyId, questionId))
      case _                                  => None
    SessionData(row.id, row.userId, row.locale, row.phase, currentQuestion)

  val layer: ZLayer[QuillSqlite, Nothing, SessionService] = ZLayer.fromFunction(apply)
end SessionService
