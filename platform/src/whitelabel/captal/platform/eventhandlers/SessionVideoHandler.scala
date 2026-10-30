package whitelabel.captal.platform.eventhandlers

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.core.video.Event as VideoEvent
import whitelabel.captal.platform.VideoViewRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.{SessionContext, SessionService}
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
          _ <-
            ZIO.foreachDiscard(videoAssignments): (videoId, advertiserIdOpt) =>
              run(
                SessionService.updateCurrentVideoQuery(lift(sessionData.sessionId), lift(videoId)))
                .orDie *> (
                advertiserIdOpt match
                  case Some(advId) =>
                    run(
                      SessionService.updateCurrentAdvertiserQuery(
                        lift(sessionData.sessionId),
                        lift(advId))).orDie.unit
                  case None =>
                    ZIO.unit
              )

          // Handle VideoVisualized events
          videoVisualizations = events.flatMap(extractVideoVisualization)
          _ <-
            ZIO.foreachDiscard(videoVisualizations): viz =>
              for
                instant <- Clock.instant
                now     = instant.toString
                viewRow = VideoViewRow(
                  id = UUID.randomUUID.toString,
                  sessionId = viz.sessionId,
                  userId = viz.userId,
                  videoId = viz.videoId,
                  durationWatchedSeconds = viz.durationWatched,
                  completed =
                    if viz.completed then
                      1
                    else
                      0
                  ,
                  viewedAt = viz.occurredAt.toString,
                  createdAt = now,
                  deviceId = Some(sessionData.deviceId)
                )
                _ <- run(query[VideoViewRow].insertValue(lift(viewRow))).orDie
              // Don't clear currentVideoId — it's needed during AdvertiserVideoSurvey phase
              // It will be overwritten when a new video is assigned
              yield ()
        yield ()
        end for
      end handle

  private case class VideoVisualizationData(
      sessionId: whitelabel.captal.core.user.SessionId,
      userId: Option[whitelabel.captal.core.user.Id],
      videoId: whitelabel.captal.core.video.Id,
      durationWatched: Int,
      completed: Boolean,
      occurredAt: java.time.Instant)

  private def extractVideoAssignment(
      event: Event): Option[(whitelabel.captal.core.video.Id, Option[String])] =
    event match
      case Event.User(UserEvent.VideoAssigned(_, videoId, advertiserId, _, _)) =>
        Some((videoId, advertiserId.map(_.asString)))
      case _ =>
        None

  private def extractVideoVisualization(event: Event): Option[VideoVisualizationData] =
    event match
      case Event.Video(
            VideoEvent.VideoVisualized(
              sessionId,
              userId,
              videoId,
              _,
              _,
              durationWatched,
              completed,
              occurredAt)) =>
        Some(
          VideoVisualizationData(
            sessionId,
            userId,
            videoId,
            durationWatched,
            completed,
            occurredAt))
      case _ =>
        None
end SessionVideoHandler
