package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{FallbackPhase, NextStep}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.question.{FullyQualifiedQuestionId, QuestionToAnswer}
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.core.{Op as CoreOp, survey, video}

final case class ProvideNextAdvertiserSurveyCommand(videoId: video.Id)

final case class NextAdvertiserSurvey(
    surveyId: survey.Id,
    advertiserId: survey.AdvertiserId,
    question: QuestionToAnswer)

object NextAdvertiserSurvey:
  given Encoder[NextAdvertiserSurvey] = Encoder.AsObject.derived
  given Decoder[NextAdvertiserSurvey] = Decoder.derived

object ProvideNextAdvertiserSurveyHandler:
  type Response = NextAdvertiserSurvey | NextStep

  def apply[F[_]: Monad](
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F],
      fallback: FallbackPhase): Handler.Aux[F, ProvideNextAdvertiserSurveyCommand, Response] =
    new Handler[F, ProvideNextAdvertiserSurveyCommand]:
      type Result = Response

      def handle(cmd: ProvideNextAdvertiserSurveyCommand) =
        for
          nextOpt <- surveyRepo.findNextAdvertiserSurvey(cmd.videoId)
          userOpt <- userRepo.findWithEmail()
        yield (userOpt, nextOpt) match
          case (Some(user), Some(next)) =>
            val question = FullyQualifiedQuestionId(next.surveyId, next.question.id)
            val event = UserEvent.VideoSurveyAssigned(
              user.id,
              next.advertiserId,
              question,
              Instant.now)
            CoreOp
              .emit[whitelabel.captal.core.user.Event, whitelabel.captal.core.user.Error](event)
              .convertEvent
              .convertError
              .as(next: Response)
          case (_, None) =>
            // No more questions for this advertiser — redirect to fallback
            CoreOp.pure(NextStep(fallback.phase): Response)
          case (None, _) =>
            CoreOp.pure(NextStep(fallback.phase): Response)
end ProvideNextAdvertiserSurveyHandler
