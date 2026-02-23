package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey.{State, Survey}
import whitelabel.captal.core.{survey, user}

trait SurveyRepository[F[_]]:
  def findById(id: survey.Id): F[Option[Survey[State]]]
  def findWithEmailQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithEmailQuestion]]]
  def findWithProfilingQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithProfilingQuestion]]]
  def findWithLocationQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithLocationQuestion]]]
  def findWithAdvertiserQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithAdvertiserQuestion]]]
  def findNextIdentificationSurvey(userId: user.Id): F[Option[NextIdentificationSurvey]]
