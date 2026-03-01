package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent, given}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event, NextStep}
import whitelabel.captal.core.infrastructure.SurveyRepository
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.user.ops.answerEmail
import whitelabel.captal.core.user.{Email, ops as UserOps}

final case class AnswerEmailCommand(answer: AnswerValue, occurredAt: Instant)

object AnswerEmailHandler:
  def apply[F[_]: Monad](
      surveyRepo: SurveyRepository[F],
      nextStep: NextStep): Handler.Aux[F, AnswerEmailCommand, NextStep] =
    new Handler[F, AnswerEmailCommand]:
      type Result = NextStep

      def handle(cmd: AnswerEmailCommand) =
        cmd.answer match
          case AnswerValue.Text(emailText) =>
            Email.fromString(emailText) match
              case Left(_) =>
                Monad[F].pure(
                  core.Op.fail[Event, Error, NextStep](Error.InvalidEmailFormat(emailText)))
              case Right(email) =>
                surveyRepo
                  .findAssignedEmailSurvey()
                  .map:
                    case None =>
                      core.Op.fail(Error.NoSurveyAssigned)
                    case Some(survey) =>
                      for
                        user <-
                          UserOps.createWithEmail(email, cmd.occurredAt).convertEvent.convertError
                        _ <-
                          user
                            .answerEmail(survey, cmd.answer, cmd.occurredAt)
                            .convertEvent
                            .convertError
                      yield nextStep
          case _ =>
            Monad[F].pure(
              core.Op.fail[Event, Error, NextStep](Error.InvalidEmailFormat(cmd.answer.toString)))
end AnswerEmailHandler
