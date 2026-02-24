package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{survey, user}

enum Error:
  case NoSurveyAssigned
  case UserNotIdentified
  case InvalidEmailFormat(value: String)
  case Survey(errors: NonEmptyChain[survey.Error])
  case User(errors: NonEmptyChain[user.Error])
