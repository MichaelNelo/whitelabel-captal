package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent, given}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{Session, SurveyRepository}
import whitelabel.captal.core.survey.question.{AnswerValue, QuestionAnswer}
import whitelabel.captal.core.user.ops.answerEmail
import whitelabel.captal.core.user.{DeviceId, Email, SessionId, ops as UserOps}

final case class AnswerEmailCommand(
    sessionId: SessionId,
    deviceId: DeviceId,
    locale: String,
    answer: AnswerValue,
    occurredAt: Instant)

object AnswerEmailHandler:
  def apply[F[_]](session: Session[F], surveyRepo: SurveyRepository[F])(using
      F: Monad[F]): Handler.Aux[F, AnswerEmailCommand, QuestionAnswer] =
    new Handler[F, AnswerEmailCommand]:
      type Result = QuestionAnswer

      def handle(cmd: AnswerEmailCommand) =
        for
          sessionData <- session.getSessionData
          result      <- handleCommand(cmd, sessionData.currentSurveyId, sessionData.currentQuestionId)
        yield result

      private def handleCommand(
          cmd: AnswerEmailCommand,
          surveyIdOpt: Option[core.survey.Id],
          questionIdOpt: Option[core.survey.question.Id]) =
        (surveyIdOpt, questionIdOpt) match
          case (None, _) | (_, None) =>
            F.pure(core.Op.fail[Event, Error, QuestionAnswer](Error.NoSurveyAssigned))
          case (Some(surveyId), Some(questionId)) =>
            cmd.answer match
              case AnswerValue.Text(emailText) =>
                Email.fromString(emailText) match
                  case Left(_) =>
                    F.pure(core.Op.fail[Event, Error, QuestionAnswer](
                      Error.InvalidEmailFormat(emailText)))
                  case Right(email) =>
                    surveyRepo
                      .findWithEmailQuestion(surveyId, questionId)
                      .map:
                        case None =>
                          core.Op.fail(Error.SurveyNotFound(surveyId))
                        case Some(survey) =>
                          for
                            user <- UserOps
                              .createWithEmail(cmd.sessionId, cmd.deviceId, cmd.locale, email, cmd.occurredAt)
                              .convertEvent
                              .convertError
                            answer <- user
                              .answerEmail(survey, cmd.answer, cmd.occurredAt)
                              .convertEvent
                              .convertError
                          yield answer
              case _ =>
                F.pure(core.Op.fail[Event, Error, QuestionAnswer](
                  Error.InvalidEmailFormat(cmd.answer.toString)))
end AnswerEmailHandler
