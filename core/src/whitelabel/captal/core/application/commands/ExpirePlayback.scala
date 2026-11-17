package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.PlaybackRepository
import whitelabel.captal.core.video.{Event as VideoEvent, Playback, PlaybackId}

/** Closes the playback `playback` for a page that never said it ended, so the file it holds can go.
  */
final case class ExpirePlaybackCommand(playback: PlaybackId, occurredAt: Instant)

/** Whether it closed it: only a playback still open and past its deadline ([[Playback.expiresAt]])
  * is. One that ended meanwhile, or is not due yet, is no refusal: there is nothing to close.
  */
object ExpirePlaybackHandler:
  def apply[F[_]: Monad](
      playbacks: PlaybackRepository[F]): Handler.Aux[F, ExpirePlaybackCommand, Boolean] =
    new Handler[F, ExpirePlaybackCommand]:
      type Result = Boolean

      def handle(cmd: ExpirePlaybackCommand) = playbacks
        .open(cmd.playback)
        .map: found =>
          found.filter(playback =>
            !Playback
              .expiresAt(playback.startedAt, playback.fileDurationSeconds, playback.grace)
              .isAfter(cmd.occurredAt)) match
            case Some(due) =>
              CoreOp.emit[Event, Error, Boolean](
                Event.Video(VideoEvent.PlaybackExpired(due.id, due.sessionId, cmd.occurredAt)),
                true)
            case None =>
              CoreOp.emitAll[Event, Error, Boolean](Nil, false)
end ExpirePlaybackHandler
