package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{survey, user}

object conversions:
  given userEventConversion: Conversion[user.Event, Event] = Event.User(_)
  given surveyEventConversion: Conversion[survey.Event, Event] = Event.Survey(_)

  given userErrorConversion: Conversion[NonEmptyChain[user.Error], Error] = Error.User(_)
  given surveyErrorConversion: Conversion[NonEmptyChain[survey.Error], Error] = Error.Survey(_)
