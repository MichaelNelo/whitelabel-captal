package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import cats.{Monad, Parallel}
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{Session, SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.{AnswerValue, QuestionAnswer}
import whitelabel.captal.core.user.ops.*

final case class AnswerProfilingCommand(answer: AnswerValue, occurredAt: Instant)

object AnswerProfilingHandler:
  def apply[F[_]](
      session: Session[F],
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F])(using
      F: Monad[F],
      P: Parallel[F]): Handler.Aux[F, AnswerProfilingCommand, QuestionAnswer] =
    new Handler[F, AnswerProfilingCommand]:
      type Result = QuestionAnswer

      def handle(cmd: AnswerProfilingCommand) =
        for
          sessionData <- session.getSessionData
          result <-
            (sessionData.currentSurveyId, sessionData.currentQuestionId) match
              case (None, _) | (_, None) =>
                F.pure(core.Op.fail[Event, Error, QuestionAnswer](Error.NoSurveyAssigned))
              case (Some(surveyId), Some(questionId)) =>
                val findUser = userRepo.findAnswering(sessionData.userId, questionId)
                val findSurvey = surveyRepo.findWithProfilingQuestion(surveyId, questionId)
                (findUser, findSurvey)
                  .parTupled
                  .map:
                    case (None, _) =>
                      core.Op.fail(Error.UserNotFound(sessionData.userId))
                    case (_, None) =>
                      core.Op.fail(Error.SurveyNotFound(surveyId))
                    case (Some(user), Some(survey)) =>
                      user
                        .answerProfiling(survey, cmd.answer, cmd.occurredAt)
                        .convertEvent
                        .convertError
        yield result
end AnswerProfilingHandler
