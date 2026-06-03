package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, FallbackPhase, NextStep}
import whitelabel.captal.core.infrastructure.{UserRepository, VideoRepository}
import whitelabel.captal.core.user.ops.*
import whitelabel.captal.core.video.VideoToWatch
import whitelabel.captal.core.{Op as CoreOp, video}

final case class ProvideNextVideoCommand(occurredAt: Instant)

final case class NextVideo(
    videoUrl: String,
    durationSeconds: Int,
    title: Option[String],
    description: Option[String])

object NextVideo:
  given Encoder[NextVideo] = Encoder.AsObject.derived
  given Decoder[NextVideo] = Decoder.derived

  def fromVideoToWatch(v: VideoToWatch): NextVideo = NextVideo(
    v.videoUrl,
    v.durationSeconds,
    v.title.map(_.value),
    v.description.map(_.value))

object ProvideNextVideoHandler:
  type Response = NextVideo | NextStep

  def apply[F[_]: Monad](
      videoRepo: VideoRepository[F],
      userRepo: UserRepository[F],
      fallback: FallbackPhase): Handler.Aux[F, ProvideNextVideoCommand, Response] =
    new Handler[F, ProvideNextVideoCommand]:
      type Result = Response

      def handle(cmd: ProvideNextVideoCommand) =
        for
          userOpt  <- userRepo.findWithEmail()
          videoOpt <- videoRepo.findNextForUser(
            userOpt.map(_.id),
            None
          ) // TODO: get lastPromoVideoId from session
          result <- handleVideoResult(cmd, userOpt, videoOpt)
        yield result

      private def handleVideoResult(
          cmd: ProvideNextVideoCommand,
          userOpt: Option[
            whitelabel.captal.core.user.User[whitelabel.captal.core.user.State.WithEmail]],
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
                      cmd.occurredAt)
                    .convertEvent
                    .convertError
                    .as(NextVideo.fromVideoToWatch(videoToWatch): Response))
              case None =>
                // No video available — redirect to the fallback phase
                Monad[F].pure(CoreOp.pure(NextStep(fallback.phase): Response))
          case None =>
            // Reaching AdvertiserVideo without a verified email means identification was
            // either skipped (no surveys seeded) or short-circuited. Surface the condition
            // so the SPA can recover, instead of silently sending the user to Ready.
            Monad[F].pure(CoreOp.fail[whitelabel.captal.core.application.Event, Error, Response](
              Error.UserNotIdentified))
end ProvideNextVideoHandler
