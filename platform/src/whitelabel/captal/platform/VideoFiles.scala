package whitelabel.captal.platform

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

import io.getquill.*
import whitelabel.captal.core.{user, video}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import zio.*

/** A video's files and the playbacks that keep them, shared by the portal, which starts and ends
  * playbacks, and the backoffice, which replaces files and deletes the ones nobody plays.
  *
  * A file is current while it is the newest confirmed one, and a file that stops being current
  * never becomes current again. Both writes that decide whether an object may be deleted hold to
  * that in a single statement: a playback is only inserted on the current file, and a file is only
  * marked deleted once it is not current and has no open playback. rqlite applies statements one at
  * a time, so whichever runs second sees the first, and a guest cannot start on a file in the
  * moment it is being deleted.
  */
object VideoFiles:

  /** `confirmed_at` is compared as text to find the current file, so every value written has the
    * same width: `Instant.toString` drops trailing zeros, and "…:41Z" sorts after "…:41.5Z".
    */
  private val Stamp = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
    .withZone(ZoneOffset.UTC)

  def stamp(instant: Instant): String = Stamp.format(instant)

  /** The file a video plays, if it has one confirmed. */
  inline def currentQuery = quote: (videoIdParam: video.Id) =>
    query[VideoFileRow]
      .filter(f => f.videoId == videoIdParam && f.confirmedAt.isDefined)
      .sortBy(_.confirmedAt)(using Ord.desc)
      .take(1)

  inline def openPlaybacksQuery = quote: (sessionIdParam: user.SessionId) =>
    query[VideoPlaybackRow].filter(p => p.sessionId == sessionIdParam && p.endedAt.isEmpty)

  /** Records a playback on `fileUrl` only while it is the video's current file. Answers whether the
    * playback is recorded, including by an earlier call with the same id.
    */
  def start(
      quill: QuillSqlite,
      playbackId: String,
      sessionId: user.SessionId,
      videoId: video.Id,
      fileUrl: String,
      at: Instant): Task[Boolean] =
    import quill.*
    val startedAt = stamp(at)
    val session = sessionId.asString
    val videoKey = videoId.asString
    for
      inserted <- run(
        quote(
          sql"""INSERT OR IGNORE INTO video_playbacks (id, session_id, file_id, started_at, ended_at)
                SELECT ${lift(playbackId)}, ${lift(session)}, f.id, ${lift(startedAt)}, NULL
                FROM video_files f
                WHERE f.video_id = ${lift(videoKey)} AND f.url = ${lift(fileUrl)}
                  AND f.deleted_at IS NULL
                  AND f.confirmed_at = (
                    SELECT MAX(c.confirmed_at) FROM video_files c
                    WHERE c.video_id = f.video_id AND c.confirmed_at IS NOT NULL)""".as[Insert[
            VideoPlaybackRow]]))
      recorded <-
        if inserted > 0 then
          ZIO.succeed(true)
        else
          run(query[VideoPlaybackRow].filter(_.id == lift(playbackId)).map(_.id)).map(_.nonEmpty)
    yield recorded
    end for
  end start

  /** Ends a playback of this session; one already ended, or another session's, is left alone. */
  def end(
      quill: QuillSqlite,
      playbackId: String,
      sessionId: user.SessionId,
      at: Instant): Task[Long] =
    import quill.*
    run(
      query[VideoPlaybackRow]
        .filter(p =>
          p.id == lift(playbackId) && p.sessionId == lift(sessionId) && p.endedAt.isEmpty)
        .update(_.endedAt -> lift(Option(stamp(at)))))

  /** Closes a playback its page never ended. Answers whether it was still open. */
  def expire(quill: QuillSqlite, playbackId: String, at: Instant): Task[Boolean] =
    import quill.*
    run(
      query[VideoPlaybackRow]
        .filter(p => p.id == lift(playbackId) && p.endedAt.isEmpty)
        .update(_.endedAt -> lift(Option(stamp(at))), _.expired -> 1)).map(_ > 0)

  /** Marks a file deleted when it is no longer current and nobody is playing it. Answers whether it
    * was marked, which is the caller's permission to delete its object.
    */
  def condemn(quill: QuillSqlite, fileId: String, at: Instant): Task[Boolean] =
    import quill.*
    val deletedAt = stamp(at)
    run(
      quote(
        sql"""UPDATE video_files SET deleted_at = ${lift(deletedAt)}
              WHERE id = ${lift(fileId)} AND deleted_at IS NULL AND confirmed_at IS NOT NULL
                AND EXISTS (
                  SELECT 1 FROM video_files n
                  WHERE n.video_id = video_files.video_id AND n.confirmed_at > video_files.confirmed_at)
                AND NOT EXISTS (
                  SELECT 1 FROM video_playbacks p
                  WHERE p.file_id = video_files.id AND p.ended_at IS NULL)""".as[Update[
          VideoFileRow]])).map(_ > 0)
end VideoFiles
