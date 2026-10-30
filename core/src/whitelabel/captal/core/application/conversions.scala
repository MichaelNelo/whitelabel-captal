package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{access, device, recovery, survey, user, video}

object conversions:
  given userEventConversion: Conversion[user.Event, Event] = Event.User(_)
  given surveyEventConversion: Conversion[survey.Event, Event] = Event.Survey(_)
  given videoEventConversion: Conversion[video.Event, Event] = Event.Video(_)
  given deviceEventConversion: Conversion[device.Event, Event] = Event.Device(_)
  given recoveryEventConversion: Conversion[recovery.Event, Event] = Event.Recovery(_)
  given accessEventConversion: Conversion[access.Event, Event] = Event.Access(_)
  given recoveryErrorConversion: Conversion[NonEmptyChain[recovery.Error], Error] =
    Error.Recovery(_)

  given userErrorConversion: Conversion[NonEmptyChain[user.Error], Error] = Error.User(_)
  given surveyErrorConversion: Conversion[NonEmptyChain[survey.Error], Error] = Error.Survey(_)
  given videoErrorConversion: Conversion[NonEmptyChain[video.Error], Error] = Error.Video(_)
