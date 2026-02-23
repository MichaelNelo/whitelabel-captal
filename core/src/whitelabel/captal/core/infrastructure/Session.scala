package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.{survey, user}

final case class SessionData(
    sessionId: user.SessionId,
    userId: user.Id,
    locale: String,
    currentSurveyId: Option[survey.Id],
    currentQuestionId: Option[survey.question.Id])

trait Session[F[_]]:
  def getSessionData: F[SessionData]
  def setCurrentSurvey(surveyId: survey.Id, questionId: survey.question.Id): F[Unit]

trait SessionRepository[F[_]]:
  def findById(sessionId: user.SessionId): F[Option[SessionData]]
  def setCurrentSurvey(
      sessionId: user.SessionId,
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Unit]
