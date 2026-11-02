package whitelabel.captal.core.video

import java.time.Instant
import java.util.UUID

import whitelabel.captal.core.user

opaque type PlaybackId = UUID
object PlaybackId:
  def apply(value: UUID): PlaybackId = value
  def fromString(s: String): Option[PlaybackId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: PlaybackId)
    def value: UUID = id
    def asString: String = id.toString

/** One guest playing one file of a video, from the moment the player is given the file's URL until
  * the guest reports the video watched or leaves the page.
  *
  * The id is the client's: a page that reloads mid-video still holds it, so it neither counts the
  * same viewer twice nor loses the one it counted.
  */
final case class Playback(
    id: PlaybackId,
    sessionId: user.SessionId,
    videoId: Id,
    fileUrl: String,
    startedAt: Instant,
    endedAt: Option[Instant]):
  def isOpen: Boolean = endedAt.isEmpty

object Playback:
  /** Room for an intro, a pause, a slow connection: a page that has not said it ended this long
    * after the file would have finished is taken to have died without saying so. A location may set
    * its own.
    */
  val Grace: java.time.Duration = java.time.Duration.ofMinutes(10)

  /** When a playback still open is closed for its page: its file's length plus `grace`. */
  def expiresAt(startedAt: Instant, fileDurationSeconds: Int, grace: java.time.Duration): Instant =
    startedAt.plusSeconds(fileDurationSeconds.toLong).plus(grace)

/** One uploaded file of a video. A video plays its current file, the newest confirmed one; the
  * others stay only while someone is still playing them.
  *
  * @param watching
  *   playbacks of this file still open.
  */
final case class VideoFile(url: String, current: Boolean, watching: Int):
  /** Whether its object can be deleted: replaced, and nobody left watching it. */
  def collectable: Boolean = !current && watching == 0
