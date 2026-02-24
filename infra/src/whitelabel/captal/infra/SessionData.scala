package whitelabel.captal.infra

import whitelabel.captal.core.{application, survey, user}

final case class SessionData(
    sessionId: user.SessionId,
    userId: Option[user.Id],
    locale: String,
    phase: application.Phase,
    currentSurveyId: Option[survey.Id],
    currentQuestionId: Option[survey.question.Id])
