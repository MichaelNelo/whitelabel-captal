package whitelabel.captal.core.application

import whitelabel.captal.core.{survey, user}

enum Event:
  case Survey(event: survey.Event)
  case User(event: user.Event)
