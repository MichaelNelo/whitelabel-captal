package whitelabel.captal.core.user

import whitelabel.captal.core.survey.question.QuestionId

enum State:
  /** New user, needs to register email */
  case PendingEmail(sessionId: SessionId, deviceId: DeviceId, locale: String)

  /** Email registered, pending validation */
  case WithEmail(email: Email, sessionId: SessionId, locale: String)

  /** Ready to answer questions */
  case ReadyToAnswer(
      email: Email,
      sessionId: SessionId,
      locale: String,
      pendingQuestions: List[QuestionId])

  /** Answering a specific question */
  case AnsweringQuestion(sessionId: SessionId, locale: String, questionId: QuestionId)
