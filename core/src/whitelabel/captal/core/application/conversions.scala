package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{survey, user, video}

object conversions:
  given userEventConversion: Conversion[user.Event, Event] = Event.User(_)
  given surveyEventConversion: Conversion[survey.Event, Event] = Event.Survey(_)
  given videoEventConversion: Conversion[video.Event, Event] = Event.Video(_)

  given userErrorConversion: Conversion[NonEmptyChain[user.Error], Error] = Error.User(_)
  given surveyErrorConversion: Conversion[NonEmptyChain[survey.Error], Error] = Error.Survey(_)
  given videoErrorConversion: Conversion[NonEmptyChain[video.Error], Error] = Error.Video(_)
