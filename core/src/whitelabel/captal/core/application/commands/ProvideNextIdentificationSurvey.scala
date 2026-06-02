package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.IdentificationSurveyType.given
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{FallbackPhase, IdentificationSurveyType, NextStep}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.question.{FullyQualifiedQuestionId, QuestionToAnswer}
import whitelabel.captal.core.user.ops.*
import whitelabel.captal.core.{Op as CoreOp, survey}

final case class ProvideNextIdentificationSurveyCommand(occurredAt: Instant)

final case class NextIdentificationSurvey(
    surveyId: survey.Id,
    surveyType: IdentificationSurveyType,
    question: QuestionToAnswer)

object NextIdentificationSurvey:
  given Encoder[NextIdentificationSurvey] = Encoder.AsObject.derived
  given Decoder[NextIdentificationSurvey] = Decoder.derived

object ProvideNextIdentificationSurveyHandler:
  type Response = NextIdentificationSurvey | NextStep

  def apply[F[_]: Monad](
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F],
      fallback: FallbackPhase)
      : Handler.Aux[F, ProvideNextIdentificationSurveyCommand, Response] =
    new Handler[F, ProvideNextIdentificationSurveyCommand]:
      type Result = Response

      def handle(cmd: ProvideNextIdentificationSurveyCommand) =
        for
          nextOpt <- surveyRepo.findNextIdentificationSurvey()
          result  <- handleSurveyResult(cmd, nextOpt)
        yield result

      private def handleSurveyResult(
          cmd: ProvideNextIdentificationSurveyCommand,
          nextOpt: Option[NextIdentificationSurvey]) =
        for userOpt <- userRepo.findWithEmail()
        yield (userOpt, nextOpt) match
          case (None, Some(next)) =>
            val nextQuestion = Some(FullyQualifiedQuestionId(next.surveyId, next.question.id))
            createGuest(nextQuestion, cmd.occurredAt).convertEvent.convertError.as(next: Response)
          case (None, None) =>
            createGuest(None, cmd.occurredAt)
              .convertEvent
              .convertError
              .as(NextStep(fallback.phase): Response)
          case (Some(existingUser), Some(next)) =>
            val nextQuestion = Some(FullyQualifiedQuestionId(next.surveyId, next.question.id))
            existingUser
              .assignSurvey(nextQuestion, fallback.phase, cmd.occurredAt)
              .convertEvent
              .convertError
              .as(next: Response)
          case (Some(existingUser), None) =>
            existingUser
              .assignSurvey(None, fallback.phase, cmd.occurredAt)
              .convertEvent
              .convertError
              .as(NextStep(fallback.phase): Response)
end ProvideNextIdentificationSurveyHandler
