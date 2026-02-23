package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import cats.{Monad, Parallel}
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.infrastructure.{Session, SurveyRepository, UserRepository}
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.{AnswerValue, QuestionAnswer}
import whitelabel.captal.core.user.ops.*

final case class AnswerAdvertiserCommand(
    surveyId: survey.Id,
    questionId: survey.question.Id,
    answer: AnswerValue,
    occurredAt: Instant)

object AnswerAdvertiserHandler:
  def apply[F[_]](
      session: Session[F],
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F])(using
      F: Monad[F],
      P: Parallel[F]): Handler.Aux[F, AnswerAdvertiserCommand, QuestionAnswer] =
    new Handler[F, AnswerAdvertiserCommand]:
      type Result = QuestionAnswer

      def handle(cmd: AnswerAdvertiserCommand) =
        for
          sessionData <- session.getSessionData
          findUser   = userRepo.findAnswering(sessionData.userId, cmd.questionId)
          findSurvey = surveyRepo.findWithAdvertiserQuestion(cmd.surveyId, cmd.questionId)
          (userOpt, surveyOpt) <- (findUser, findSurvey).parTupled
        yield (userOpt, surveyOpt) match
          case (None, _) =>
            core.Op.fail(Error.UserNotFound(sessionData.userId))
          case (_, None) =>
            core.Op.fail(Error.SurveyNotFound(cmd.surveyId))
          case (Some(user), Some(survey)) =>
            user.answerAdvertiser(survey, cmd.answer, cmd.occurredAt).convertEvent.convertError
end AnswerAdvertiserHandler
