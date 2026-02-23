package whitelabel.captal.core.application.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.infrastructure.{Session, SurveyRepository}
import whitelabel.captal.core.user.SessionId
import whitelabel.captal.core.{Op as CoreOp, survey, user}

final case class ProvideNextIdentificationSurveyCommand(userId: user.Id, sessionId: SessionId)

enum IdentificationSurveyType:
  case Email,
    Profiling,
    Location

final case class NextIdentificationSurvey(
    surveyId: survey.Id,
    questionId: survey.question.Id,
    surveyType: IdentificationSurveyType)

object ProvideNextIdentificationSurveyHandler:
  type Response = Option[NextIdentificationSurvey]

  def apply[F[_]](session: Session[F], surveyRepo: SurveyRepository[F])(using
      F: Monad[F]): Handler.Aux[F, ProvideNextIdentificationSurveyCommand, Response] =
    new Handler[F, ProvideNextIdentificationSurveyCommand]:
      type Result = Response

      def handle(cmd: ProvideNextIdentificationSurveyCommand) =
        for
          nextSurveyOpt <- surveyRepo.findNextIdentificationSurvey(cmd.userId)
          _             <-
            nextSurveyOpt match
              case Some(next) =>
                session.setCurrentSurvey(next.surveyId, next.questionId)
              case None =>
                F.unit
        yield CoreOp.pure(nextSurveyOpt)
