package whitelabel.captal.core.application

import whitelabel.captal.core.{
  access,
  device,
  i18n,
  location,
  operation,
  recovery,
  session,
  survey,
  terms,
  user,
  video
}

enum Event:
  case Survey(event: survey.Event)
  case User(event: user.Event)
  case Video(event: video.Event)
  case Device(event: device.Event)
  case Recovery(event: recovery.Event)
  case Access(event: access.Event)
  case Location(event: location.Event)
  case Operation(event: operation.Event)
  case Session(event: session.Event)
  case Terms(event: terms.Event)
  case I18n(event: i18n.Event)
