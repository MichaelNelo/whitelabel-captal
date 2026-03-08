package whitelabel.captal.infra.eventhandlers

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.core.video.{Event as VideoEvent, VideoType}
import whitelabel.captal.infra.VideoViewRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

object SessionVideoHandler:
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*

        for
          sessionData <- ctx.getOrFail
          // Handle VideoAssigned events
          videoAssignments = events.flatMap(extractVideoAssignment)
          _ <- ZIO.foreachDiscard(videoAssignments): videoId =>
            run(SessionService.updateCurrentVideoQuery(lift(sessionData.sessionId), lift(videoId)))
              .orDie

          // Handle VideoVisualized events
          videoVisualizations = events.flatMap(extractVideoVisualization)
          _ <- ZIO.foreachDiscard(videoVisualizations): viz =>
            val now = java.time.Instant.now.toString
            val viewRow = VideoViewRow(
              id = UUID.randomUUID.toString,
              sessionId = viz.sessionId,
              userId = viz.userId,
              videoId = viz.videoId,
              durationWatchedSeconds = viz.durationWatched,
              completed = if viz.completed then 1 else 0,
              viewedAt = viz.occurredAt.toString,
              createdAt = now
            )
            for
              // Insert video view
              _ <- run(query[VideoViewRow].insertValue(lift(viewRow))).orDie
              // Clear current video
              _ <- run(SessionService.clearCurrentVideoQuery(lift(sessionData.sessionId))).orDie
              // Update last propaganda video if applicable
              _ <-
                if viz.videoType == VideoType.Promo then
                  run(
                    SessionService.updateLastPromoVideoQuery(
                      lift(sessionData.sessionId),
                      lift(viz.videoId))).orDie
                else
                  ZIO.unit
            yield ()
        yield ()

  private case class VideoVisualizationData(
      sessionId: whitelabel.captal.core.user.SessionId,
      userId: Option[whitelabel.captal.core.user.Id],
      videoId: whitelabel.captal.core.video.Id,
      videoType: VideoType,
      durationWatched: Int,
      completed: Boolean,
      occurredAt: java.time.Instant)

  private def extractVideoAssignment(event: Event): Option[whitelabel.captal.core.video.Id] =
    event match
      case Event.User(UserEvent.VideoAssigned(_, videoId, _, _, _)) =>
        Some(videoId)
      case _ =>
        None

  private def extractVideoVisualization(event: Event): Option[VideoVisualizationData] =
    event match
      case Event.Video(VideoEvent.VideoVisualized(
            sessionId,
            userId,
            videoId,
            _,
            videoType,
            durationWatched,
            completed,
            occurredAt)) =>
        Some(
          VideoVisualizationData(
            sessionId,
            userId,
            videoId,
            videoType,
            durationWatched,
            completed,
            occurredAt))
      case _ =>
        None
end SessionVideoHandler
