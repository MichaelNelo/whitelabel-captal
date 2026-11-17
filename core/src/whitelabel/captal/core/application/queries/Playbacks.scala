package whitelabel.captal.core.application.queries

import java.time.Instant

/** The playbacks not ended yet — the playback `playback` alone, or every one — with when each is
  * due to be closed for a page that never said it ended.
  */
final case class OpenPlaybacksQuery(playback: Option[String])

final case class OpenPlaybackReport(id: String, expiresAt: Instant)
