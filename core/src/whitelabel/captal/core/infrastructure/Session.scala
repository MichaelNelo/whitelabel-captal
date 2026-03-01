package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.{application, survey, user}
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId

final case class SessionData(
    sessionId: user.SessionId,
    userId: Option[user.Id],
    locale: String,
    phase: application.Phase,
    currentQuestion: Option[FullyQualifiedQuestionId])
