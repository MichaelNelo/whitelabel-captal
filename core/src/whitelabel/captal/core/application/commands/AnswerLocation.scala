package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.{AnswerValue, QuestionAnswer}
import whitelabel.captal.core.user.ops.*

final case class AnswerLocationCommand(answer: AnswerValue, occurredAt: Instant)

object AnswerLocationHandler:
  def apply[F[_]: Monad](
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F]): Handler.Aux[F, AnswerLocationCommand, QuestionAnswer] =
    new Handler[F, AnswerLocationCommand]:
      type Result = QuestionAnswer

      def handle(cmd: AnswerLocationCommand) = userRepo
        .findAnswering()
        .flatMap:
          case None =>
            Monad[F].pure(core.Op.fail[Event, Error, QuestionAnswer](Error.UserNotIdentified))
          case Some(user) =>
            surveyRepo
              .findWithLocationQuestion(user.state.surveyId, user.state.questionId)
              .map:
                case None =>
                  core.Op.fail(Error.NoSurveyAssigned)
                case Some(survey) =>
                  user.answerLocation(survey, cmd.answer, cmd.occurredAt).convertEvent.convertError
end AnswerLocationHandler
