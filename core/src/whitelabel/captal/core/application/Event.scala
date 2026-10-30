package whitelabel.captal.core.application

import whitelabel.captal.core.{access, device, recovery, survey, user, video}

enum Event:
  case Survey(event: survey.Event)
  case User(event: user.Event)
  case Video(event: video.Event)
  case Device(event: device.Event)
  case Recovery(event: recovery.Event)
  case Access(event: access.Event)
