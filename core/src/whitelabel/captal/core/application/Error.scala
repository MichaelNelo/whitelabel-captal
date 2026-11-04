package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{recovery, survey, user, video}

enum Error:
  case NoSurveyAssigned
  case UserNotIdentified

  /** The device has not accepted the terms of service in force. */
  case TermsNotAccepted

  /** No version of the terms of service exists to accept. */
  case NoTermsOfService
  case InvalidEmailFormat(value: String)
  case Survey(errors: NonEmptyChain[survey.Error])
  case User(errors: NonEmptyChain[user.Error])
  case Video(errors: NonEmptyChain[video.Error])
  case Recovery(errors: NonEmptyChain[recovery.Error])
