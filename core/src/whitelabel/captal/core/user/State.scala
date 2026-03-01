package whitelabel.captal.core.user

import whitelabel.captal.core.survey

enum State:
  case Guest
  case WithEmail(email: Email)
  case AnsweringQuestion(surveyId: survey.Id, questionId: survey.question.Id)
