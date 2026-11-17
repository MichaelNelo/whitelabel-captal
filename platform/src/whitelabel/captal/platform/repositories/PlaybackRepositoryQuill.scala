package whitelabel.captal.platform.repositories

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.infrastructure.{OpenPlayback, PlaybackRepository}
import whitelabel.captal.core.video.PlaybackId
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRecovery, SessionRow, VideoFileRow, VideoPlaybackRow}
import zio.*

/** An open playback with what its deadline is reckoned from: its file's length, and the grace of
  * the location its session is at — the default for a session that names none.
  */
object PlaybackRepositoryQuill:
  def apply(quill: QuillSqlite): PlaybackRepository[Task] =
    import quill.*
    new PlaybackRepository[Task]:
      def open(id: PlaybackId): Task[Option[OpenPlayback]] =
        for
          found <- run(
            query[VideoPlaybackRow]
              .filter(p => p.id == lift(id.asString) && p.endedAt.isEmpty)
              .join(query[VideoFileRow])
              .on(_.fileId == _.id)
              .leftJoin(query[SessionRow])
              .on((pf, s) => pf._1.sessionId == s.id)
              .map((pf, s) =>
                (pf._1.sessionId, pf._1.startedAt, pf._2.durationSeconds, s.flatMap(_.locationId))))
          playback <-
            ZIO.foreach(found.headOption): (session, startedAt, duration, location) =>
              ZIO
                .foreach(location)(LocationRecovery.find(quill, _))
                .map(row =>
                  OpenPlayback(
                    id,
                    session,
                    Instant.parse(startedAt),
                    duration.getOrElse(0),
                    LocationRecovery.grace(row.flatten)))
        yield playback
    end new
  end apply
end PlaybackRepositoryQuill
