package whitelabel.captal.core.user

import whitelabel.captal.core.survey

enum State:
  case PendingEmail(sessionId: SessionId, deviceId: DeviceId, locale: String)
  case WithEmail(email: Email, sessionId: SessionId, locale: String)
  case ReadyToAnswer(
      email: Email,
      sessionId: SessionId,
      locale: String,
      pendingQuestions: List[survey.question.Id])
  case AnsweringQuestion(sessionId: SessionId, locale: String, questionId: survey.question.Id)
