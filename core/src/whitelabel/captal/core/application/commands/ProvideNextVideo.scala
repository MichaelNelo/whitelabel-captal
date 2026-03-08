package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{NextStep, Phase}
import whitelabel.captal.core.infrastructure.{UserRepository, VideoRepository}
import whitelabel.captal.core.user.ops.*
import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{Op as CoreOp, video}

case object ProvideNextVideoCommand

final case class NextVideo(
    videoUrl: String,
    durationSeconds: Int,
    title: Option[String],
    description: Option[String])

object NextVideo:
  given Encoder[NextVideo] = Encoder.AsObject.derived
  given Decoder[NextVideo] = Decoder.derived

  def fromVideoToWatch(v: VideoToWatch): NextVideo =
    NextVideo(
      v.videoUrl,
      v.durationSeconds,
      v.title.map(_.value),
      v.description.map(_.value))

object ProvideNextVideoHandler:
  type Response = NextVideo | NextStep

  def apply[F[_]: Monad](
      videoRepo: VideoRepository[F],
      userRepo: UserRepository[F],
      terminalPhase: Phase): Handler.Aux[F, ProvideNextVideoCommand.type, Response] =
    new Handler[F, ProvideNextVideoCommand.type]:
      type Result = Response

      def handle(cmd: ProvideNextVideoCommand.type) =
        for
          userOpt  <- userRepo.findWithEmail()
          videoOpt <- videoRepo.findNextForUser(userOpt.map(_.id), None) // TODO: get lastPromoVideoId from session
          result   <- handleVideoResult(userOpt, videoOpt)
        yield result

      private def handleVideoResult(
          userOpt: Option[whitelabel.captal.core.user.User[whitelabel.captal.core.user.State.WithEmail]],
          videoOpt: Option[VideoToWatch]) =
        userOpt match
          case Some(user) =>
            videoOpt match
              case Some(videoToWatch) =>
                // Assign video to user
                Monad[F].pure(
                  user
                    .assignVideo(
                      videoToWatch.id,
                      videoToWatch.advertiserId,
                      videoToWatch.videoType,
                      Instant.now)
                    .convertEvent
                    .convertError
                    .as(NextVideo.fromVideoToWatch(videoToWatch): Response)
                )
              case None =>
                // No video available, go to terminal phase
                Monad[F].pure(CoreOp.pure(NextStep(terminalPhase): Response))
          case None =>
            // User not identified, shouldn't happen in this phase but handle gracefully
            Monad[F].pure(CoreOp.pure(NextStep(terminalPhase): Response))
end ProvideNextVideoHandler
