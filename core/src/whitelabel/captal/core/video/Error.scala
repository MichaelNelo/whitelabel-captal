package whitelabel.captal.core.video

enum Error:
  case VideoNotFound(videoId: Id)
  case NoVideoAvailable

  /** The player was given a file the video no longer plays: it was replaced in between. */
  case StaleFile(videoId: Id, fileUrl: String)
