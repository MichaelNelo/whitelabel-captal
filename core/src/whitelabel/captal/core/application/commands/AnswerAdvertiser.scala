package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event, NextStep}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.user.ops.*

final case class AnswerAdvertiserCommand(answer: AnswerValue, occurredAt: Instant)

object AnswerAdvertiserHandler:
  def apply[F[_]](surveyRepo: SurveyRepository[F], userRepo: UserRepository[F], nextStep: NextStep)(
      using F: Monad[F]): Handler.Aux[F, AnswerAdvertiserCommand, NextStep] =
    new Handler[F, AnswerAdvertiserCommand]:
      type Result = NextStep

      def handle(cmd: AnswerAdvertiserCommand) = userRepo
        .findAnsweringVideoSurvey()
        .flatMap:
          case None =>
            F.pure(core.Op.fail[Event, Error, NextStep](Error.UserNotIdentified))
          case Some(user) =>
            surveyRepo
              .findWithAdvertiserQuestion(user.state.surveyId, user.state.questionId)
              .map:
                case None =>
                  core.Op.fail(Error.NoSurveyAssigned)
                case Some(survey) =>
                  user
                    .answerAdvertiserSurvey(survey, cmd.answer, cmd.occurredAt)
                    .convertEvent
                    .convertError
                    .as(nextStep)
end AnswerAdvertiserHandler
