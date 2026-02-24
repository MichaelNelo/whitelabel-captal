package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.{State, Survey}

trait SurveyRepository[F[_]]:
  def findAssignedEmailSurvey(): F[Option[Survey[State.WithEmailQuestion]]]
  def findWithProfilingQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithProfilingQuestion]]]
  def findWithLocationQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithLocationQuestion]]]
  def findWithAdvertiserQuestion(
      surveyId: survey.Id,
      questionId: survey.question.Id): F[Option[Survey[State.WithAdvertiserQuestion]]]
  def findNextIdentificationSurvey(): F[Option[NextIdentificationSurvey]]
