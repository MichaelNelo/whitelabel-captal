package whitelabel.captal.platform.queries

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{OpenPlaybackReport, OpenPlaybacksQuery, Query}
import whitelabel.captal.core.video.Playback
import whitelabel.captal.platform.schema.QuillSqlite
// Reported unused, but Quill's macro decodes `SessionRow`'s phase through it.
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRecovery, SessionRow, VideoFileRow, VideoPlaybackRow}
import zio.*

/** [[OpenPlaybacksQuery]]: each playback's deadline from its start, its file's length and the grace
  * of the location its session is at, read once per location.
  */
object Playbacks:
  def open(quill: QuillSqlite): Query.Aux[Task, OpenPlaybacksQuery, List[OpenPlaybackReport]] =
    new Query[Task, OpenPlaybacksQuery]:
      type Result = List[OpenPlaybackReport]

      def execute(q: OpenPlaybacksQuery): Task[Either[Error, List[OpenPlaybackReport]]] =
        import quill.*
        for
          found <- run(
            query[VideoPlaybackRow]
              .filter(_.endedAt.isEmpty)
              .join(query[VideoFileRow])
              .on(_.fileId == _.id)
              .leftJoin(query[SessionRow])
              .on((pf, s) => pf._1.sessionId == s.id)
              .map((pf, s) =>
                (pf._1.id, pf._1.startedAt, pf._2.durationSeconds, s.flatMap(_.locationId))))
          wanted = found.filter((id, _, _, _) => q.playback.forall(_ == id))
          graces <- ZIO
            .foreach(wanted.flatMap(_._4).distinct)(location =>
              LocationRecovery.find(quill, location).map(row => location -> row))
            .map(_.toMap)
        yield Right(
          wanted.map: (id, startedAt, duration, location) =>
            OpenPlaybackReport(
              id,
              Playback.expiresAt(
                Instant.parse(startedAt),
                duration.getOrElse(0),
                LocationRecovery.grace(location.flatMap(graces.get).flatten))))
        end for
      end execute
end Playbacks
