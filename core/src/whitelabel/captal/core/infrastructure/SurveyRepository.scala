package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.application.commands.{NextAdvertiserSurvey, NextIdentificationSurvey}
import whitelabel.captal.core.survey.{State, Survey}
import whitelabel.captal.core.{survey, video}

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
  def findNextAdvertiserSurvey(videoId: video.Id): F[Option[NextAdvertiserSurvey]]
