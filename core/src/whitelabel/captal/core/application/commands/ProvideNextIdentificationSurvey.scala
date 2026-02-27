package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.IdentificationSurveyType
import whitelabel.captal.core.application.IdentificationSurveyType.given
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.user.ops.*
import whitelabel.captal.core.survey.question.QuestionToAnswer
import whitelabel.captal.core.{Op as CoreOp, survey, user}

case object ProvideNextIdentificationSurveyCommand

final case class NextIdentificationSurvey(
    surveyId: survey.Id,
    surveyType: IdentificationSurveyType,
    question: QuestionToAnswer)

object NextIdentificationSurvey:
  given Encoder[NextIdentificationSurvey] = Encoder.AsObject.derived
  given Decoder[NextIdentificationSurvey] = Decoder.derived

object ProvideNextIdentificationSurveyHandler:
  type Response = Option[NextIdentificationSurvey]

  def apply[F[_]: Monad](surveyRepo: SurveyRepository[F], userRepo: UserRepository[F])
      : Handler.Aux[F, ProvideNextIdentificationSurveyCommand.type, Response] =
    new Handler[F, ProvideNextIdentificationSurveyCommand.type]:
      type Result = Response

      def handle(cmd: ProvideNextIdentificationSurveyCommand.type) =
        for
          nextOpt <- surveyRepo.findNextIdentificationSurvey()
          result  <-
            nextOpt match
              case None =>
                Monad[F].pure(CoreOp.pure(None: Response))
              case Some(next) =>
                handleNextSurvey(next)
        yield result

      private def handleNextSurvey(next: NextIdentificationSurvey) =
        for userOpt <- userRepo.findWithEmail()
        yield userOpt match
          case None =>
            CoreOp
              .emit(user.Event.NewUserArrived(next.surveyId, next.question.id, Instant.now))
              .convertEvent
              .as(Some(next): Response)
          case Some(existingUser) =>
            existingUser
              .assignSurvey(next.surveyId, next.question.id, Instant.now)
              .convertEvent
              .convertError
              .as(Some(next): Response)
end ProvideNextIdentificationSurveyHandler
