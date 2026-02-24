package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.survey

enum Event:
  case UserCreated(userId: Id, email: Email, occurredAt: Instant)
  case SurveyAssigned(
      userId: Id,
      surveyId: survey.Id,
      questionId: survey.question.Id,
      occurredAt: Instant)
  case NewUserArrived(surveyId: survey.Id, questionId: survey.question.Id, occurredAt: Instant)
