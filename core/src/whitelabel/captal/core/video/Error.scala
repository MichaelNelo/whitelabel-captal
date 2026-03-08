package whitelabel.captal.core.video

enum Error:
  case VideoNotFound(videoId: Id)
  case NoVideoAvailable
