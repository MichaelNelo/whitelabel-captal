package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{application, user, video}

final case class SessionData(
    sessionId: user.SessionId,
    userId: Option[user.Id],
    locale: String,
    phase: application.Phase,
    currentQuestion: Option[FullyQualifiedQuestionId],
    currentVideoId: Option[video.Id],
    lastPromoVideoId: Option[video.Id])
