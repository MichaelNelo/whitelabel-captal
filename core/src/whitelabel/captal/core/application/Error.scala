package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{survey, user}

enum Error:
  case NoSurveyAssigned
  case SurveyNotFound(surveyId: survey.Id)
  case UserNotFound(userId: user.Id)
  case QuestionNotInExpectedState(surveyId: survey.Id, questionId: survey.question.Id)
  case InvalidEmailFormat(value: String)
  case Survey(errors: NonEmptyChain[survey.Error])
  case User(errors: NonEmptyChain[user.Error])
