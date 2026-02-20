package whitelabel.captal.core.user

enum Error:
  case EmailAlreadyRegistered(userId: UserId)
  case InvalidEmailFormat(email: String)
  case SessionExpired(sessionId: SessionId)
  case NoPendingQuestions(userId: UserId)
  case QuestionNotPending(userId: UserId, questionId: String)
