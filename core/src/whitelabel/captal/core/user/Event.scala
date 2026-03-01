package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId

enum Event:
  case UserCreated(userId: Id, email: Email, occurredAt: Instant)
  case SurveyAssigned(userId: Id, nextQuestion: FullyQualifiedQuestionId, occurredAt: Instant)
  case NewUserArrived(
      userId: Id,
      nextQuestion: Option[FullyQualifiedQuestionId],
      occurredAt: Instant)
  case IdentificationCompleted(userId: Id, occurredAt: Instant)
