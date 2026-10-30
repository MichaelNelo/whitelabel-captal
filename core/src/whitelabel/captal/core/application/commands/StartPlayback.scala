package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.infrastructure.{SessionData, VideoRepository}
import whitelabel.captal.core.video.{Error as VideoError, Event as VideoEvent, PlaybackId}
import whitelabel.captal.core.{Op as CoreOp, video as videoPkg}

/** The player was handed a file and is about to fetch it.
  *
  * @param fileUrl
  *   the URL the player was given, which must still be the session's video's current file.
  */
final case class StartPlaybackCommand(playbackId: PlaybackId, fileUrl: String, occurredAt: Instant)

object StartPlaybackHandler:
  def withSession[F[_]: Monad](
      videoRepo: VideoRepository[F],
      session: SessionData): Handler.Aux[F, StartPlaybackCommand, Unit] =
    new Handler[F, StartPlaybackCommand]:
      type Result = Unit

      def handle(cmd: StartPlaybackCommand) =
        session.currentVideoId match
          case Some(videoId) =>
            videoRepo
              .findById(videoId)
              .map:
                case Some(current) if current.videoUrl == cmd.fileUrl =>
                  CoreOp
                    .emit[videoPkg.Event, videoPkg.Error](
                      VideoEvent.PlaybackStarted(
                        playbackId = cmd.playbackId,
                        sessionId = session.sessionId,
                        videoId = videoId,
                        fileUrl = cmd.fileUrl,
                        occurredAt = cmd.occurredAt))
                    .convertEvent
                    .convertError
                case Some(_) =>
                  CoreOp
                    .fail[videoPkg.Event, videoPkg.Error, Unit](
                      VideoError.StaleFile(videoId, cmd.fileUrl))
                    .convertEvent
                    .convertError
                case None =>
                  CoreOp
                    .fail[videoPkg.Event, videoPkg.Error, Unit](VideoError.VideoNotFound(videoId))
                    .convertEvent
                    .convertError
          case None =>
            Monad[F].pure(
              CoreOp
                .fail[videoPkg.Event, videoPkg.Error, Unit](VideoError.NoVideoAvailable)
                .convertEvent
                .convertError)
end StartPlaybackHandler
