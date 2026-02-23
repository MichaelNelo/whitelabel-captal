package whitelabel.captal.core.user

enum Error:
  case EmailAlreadyRegistered(userId: Id)
  case InvalidEmailFormat(email: String)
  case SessionExpired(sessionId: SessionId)
  case NoPendingQuestions(userId: Id)
  case QuestionNotPending(userId: Id, questionId: String)
