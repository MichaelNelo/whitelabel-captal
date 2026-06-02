package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, NextStep}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.user.ops.*

final case class AnswerProfilingCommand(answer: AnswerValue, occurredAt: Instant)

object AnswerProfilingHandler:
  def apply[F[_]](surveyRepo: SurveyRepository[F], userRepo: UserRepository[F], nextStep: NextStep)(
      using F: Monad[F]): Handler.Aux[F, AnswerProfilingCommand, NextStep] =
    new Handler[F, AnswerProfilingCommand]:
      type Result = NextStep

      def handle(cmd: AnswerProfilingCommand) =
        for
          userOpt   <- userRepo.findAnswering()
          surveyOpt <- userOpt match
            case Some(user) =>
              surveyRepo.findWithProfilingQuestion(user.state.surveyId, user.state.questionId)
            case None =>
              F.pure(None)
        yield (userOpt, surveyOpt) match
          case (None, _) =>
            core.Op.fail(Error.UserNotIdentified)
          case (Some(_), None) =>
            core.Op.fail(Error.NoSurveyAssigned)
          case (Some(user), Some(survey)) =>
            user
              .answerProfiling(survey, cmd.answer, cmd.occurredAt)
              .convertEvent
              .convertError
              .as(nextStep)
end AnswerProfilingHandler
