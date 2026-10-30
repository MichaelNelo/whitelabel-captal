package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent, given}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event, NextStep}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.user.ops.answerEmail
import whitelabel.captal.core.user.{Email, ops as UserOps}
import whitelabel.captal.core.{device, user}

/** @param deviceId
  *   the session's device, which the user the email identifies is linked to.
  */
final case class AnswerEmailCommand(
    answer: AnswerValue,
    deviceId: user.DeviceId,
    occurredAt: Instant)

object AnswerEmailHandler:
  /** An email already answered elsewhere identifies that user rather than creating another: the
    * session becomes theirs, and so does this device. Their answer to this question is the one they
    * gave then — the same email — so none is recorded now.
    */
  def apply[F[_]: Monad](
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F],
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
                for
                  assigned <- surveyRepo.findAssignedEmailSurvey()
                  known    <- userRepo.findIdByEmail(email)
                yield assigned match
                  case None =>
                    core.Op.fail(Error.NoSurveyAssigned)
                  case Some(survey) =>
                    val identified =
                      known match
                        case Some(existing) =>
                          UserOps
                            .recognize(existing, email, cmd.occurredAt)
                            .convertEvent
                            .convertError
                            .map(_.id)
                        case None =>
                          for
                            user <-
                              UserOps
                                .createWithEmail(email, cmd.occurredAt)
                                .convertEvent
                                .convertError
                            _ <-
                              user
                                .answerEmail(survey, cmd.answer, cmd.occurredAt)
                                .convertEvent
                                .convertError
                          yield user.id
                    for
                      userId <- identified
                      _      <- core
                        .Op
                        .emit[Event, Error](
                          Event.Device(
                            device.Event.DeviceLinked(userId, cmd.deviceId, cmd.occurredAt)))
                    yield nextStep
          case _ =>
            Monad[F].pure(
              core.Op.fail[Event, Error, NextStep](Error.InvalidEmailFormat(cmd.answer.toString)))
end AnswerEmailHandler
