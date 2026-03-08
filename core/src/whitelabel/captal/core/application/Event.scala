package whitelabel.captal.core.application

import whitelabel.captal.core.{survey, user, video}

enum Event:
  case Survey(event: survey.Event)
  case User(event: user.Event)
  case Video(event: video.Event)
