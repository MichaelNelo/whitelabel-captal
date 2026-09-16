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
          userOpt <- userRepo.findWithEmail()
          result  <-
            userOpt match
              case None =>
                // Reaching AdvertiserVideo without a verified email means identification was
                // either skipped (no surveys seeded) or short-circuited. Surface the condition
                // so the SPA can recover, instead of silently sending the user to Ready.
                Monad[F].pure(
                  CoreOp.fail[whitelabel.captal.core.application.Event, Error, Response](
                    Error.UserNotIdentified))
              case Some(user) =>
                // Asked for only once there is a user to ask for. Previously the lookup ran first
                // and its result was discarded on this same branch — a weighted draw against the
                // database whose answer nobody ever read.
                for videoOpt <- videoRepo.findNextForUser(user.id)
                yield videoOpt match
                  case Some(videoToWatch) =>
                    user
                      .assignVideo(
                        videoToWatch.id,
                        videoToWatch.advertiserId,
                        videoToWatch.videoType,
                        cmd.occurredAt)
                      .convertEvent
                      .convertError
                      .as(NextVideo.fromVideoToWatch(videoToWatch): Response)
                  case None =>
                    // No video available — redirect to the fallback phase
                    CoreOp.pure(NextStep(fallback.phase): Response)
        yield result
end ProvideNextVideoHandler
