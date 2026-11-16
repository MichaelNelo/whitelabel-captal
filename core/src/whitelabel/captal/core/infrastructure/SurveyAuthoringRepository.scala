package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.survey.Id as SurveyId
import whitelabel.captal.core.survey.authoring.SurveyKind
import whitelabel.captal.core.survey.question.Id as QuestionId

/** A survey as its authoring reads it. */
final case class SurveyInfo(id: SurveyId, kind: SurveyKind, active: Boolean)

/** A question as its authoring reads it, with its survey's kind. */
final case class QuestionInfo(
    id: QuestionId,
    survey: SurveyId,
    kind: SurveyKind,
    active: Boolean,
    displayOrder: Int)

/** Where a video belongs, which the survey asked after it belongs to as well. */
final case class VideoOwner(location: Option[String], advertiser: Option[String])

trait SurveyAuthoringRepository[F[_]]:
  def survey(id: SurveyId): F[Option[SurveyInfo]]
  def question(id: QuestionId): F[Option[QuestionInfo]]

  /** Its questions, retired ones included. */
  def questionsOf(survey: SurveyId): F[List[QuestionInfo]]

  /** How many answers have been given to it. */
  def answersTo(question: QuestionId): F[Int]

  /** The active survey of an identification `kind`, if there is one. */
  def activeSurveyOf(kind: SurveyKind): F[Option[SurveyId]]

  /** Where the video `id` belongs, if there is such a video. */
  def videoOwner(id: String): F[Option[VideoOwner]]
end SurveyAuthoringRepository
