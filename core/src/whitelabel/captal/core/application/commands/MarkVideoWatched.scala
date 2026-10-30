package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.infrastructure.{SessionData, VideoRepository}
import whitelabel.captal.core.video.{Error as VideoError, Event as VideoEvent}
import whitelabel.captal.core.{Op as CoreOp, video as videoPkg}

final case class MarkVideoWatchedCommand(
    durationWatched: Int,
    completed: Boolean,
    occurredAt: Instant)

object MarkVideoWatchedHandler:
  def apply[F[_]: Monad](
      videoRepo: VideoRepository[F],
      nextStep: NextStep): Handler.Aux[F, MarkVideoWatchedCommand, NextStep] =
    new Handler[F, MarkVideoWatchedCommand]:
      type Result = NextStep

      def handle(cmd: MarkVideoWatchedCommand) =
        // No session, so no video id and nothing emitted; use `withSession` to record
        // VideoVisualized.
        Monad[F].pure(CoreOp.pure(nextStep))

  def withSession[F[_]: Monad](
      videoRepo: VideoRepository[F],
      session: SessionData,
      nextStep: NextStep): Handler.Aux[F, MarkVideoWatchedCommand, NextStep] =
    new Handler[F, MarkVideoWatchedCommand]:
      type Result = NextStep

      def handle(cmd: MarkVideoWatchedCommand) =
        session.currentVideoId match
          case Some(videoId) =>
            Monad[F]
              .product(videoRepo.findById(videoId), videoRepo.openPlaybacks(session.sessionId))
              .map:
                case (Some(video), open) =>
                  val event = VideoEvent.VideoVisualized(
                    sessionId = session.sessionId,
                    userId = session.userId,
                    videoId = videoId,
                    advertiserId = video.advertiserId,
                    videoType = video.videoType,
                    durationWatched = cmd.durationWatched,
                    completed = cmd.completed,
                    occurredAt = cmd.occurredAt
                  )
                  // Watched, the file is no longer being fetched.
                  val ended = open.map(
                    VideoEvent.PlaybackEnded(_, session.sessionId, cmd.occurredAt))
                  CoreOp
                    .emitAll[videoPkg.Event, videoPkg.Error, NextStep](event :: ended, nextStep)
                    .convertEvent
                    .convertError
                case (None, _) =>
                  CoreOp
                    .fail[videoPkg.Event, videoPkg.Error, NextStep](
                      VideoError.VideoNotFound(videoId))
                    .convertEvent
                    .convertError
          case None =>
            Monad[F].pure(
              CoreOp
                .fail[videoPkg.Event, videoPkg.Error, NextStep](VideoError.NoVideoAvailable)
                .convertEvent
                .convertError)
end MarkVideoWatchedHandler
