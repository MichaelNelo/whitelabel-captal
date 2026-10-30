package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.video.{Event as VideoEvent, PlaybackId}
import whitelabel.captal.core.{Op as CoreOp, video as videoPkg}

/** The page playing it is going away. Ending a playback that already ended, or that is another
  * session's, changes nothing: the page sends this on its way out and cannot wait to be told.
  */
final case class EndPlaybackCommand(playbackId: PlaybackId, occurredAt: Instant)

object EndPlaybackHandler:
  def withSession[F[_]: Monad](session: SessionData): Handler.Aux[F, EndPlaybackCommand, Unit] =
    new Handler[F, EndPlaybackCommand]:
      type Result = Unit

      def handle(cmd: EndPlaybackCommand) = Monad[F].pure(
        CoreOp
          .emit[videoPkg.Event, videoPkg.Error](
            VideoEvent.PlaybackEnded(cmd.playbackId, session.sessionId, cmd.occurredAt))
          .convertEvent
          .convertError)
end EndPlaybackHandler
