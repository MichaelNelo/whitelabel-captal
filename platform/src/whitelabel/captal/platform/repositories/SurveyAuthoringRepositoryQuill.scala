package whitelabel.captal.platform.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.{
  QuestionInfo,
  SurveyAuthoringRepository,
  SurveyInfo,
  VideoOwner
}
import whitelabel.captal.core.survey.Id as SurveyId
import whitelabel.captal.core.survey.authoring.SurveyKind
import whitelabel.captal.core.survey.question.Id as QuestionId
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.surveySchemas.given
import whitelabel.captal.platform.{
  AdvertiserVideoRow,
  AnswerRow,
  QuestionRow,
  SurveyCategory,
  SurveyRow
}
import zio.*

/** The surveys and their questions as the authoring commands read them. A survey of a category
  * `core` has no kind for is no survey they can change, and is left out.
  */
object SurveyAuthoringRepositoryQuill:
  def apply(quill: QuillSqlite): SurveyAuthoringRepository[Task] =
    import quill.*
    new SurveyAuthoringRepository[Task]:
      def survey(id: SurveyId): Task[Option[SurveyInfo]] = run(
        query[SurveyRow].filter(_.id == lift(id))).map(
        _.flatMap(row => kindOf(row.category).map(SurveyInfo(row.id, _, row.isActive == 1)))
          .headOption)

      def question(id: QuestionId): Task[Option[QuestionInfo]] = run(
        query[QuestionRow].filter(_.id == lift(id))).flatMap:
        _.headOption match
          case None =>
            ZIO.none
          case Some(row) =>
            survey(row.surveyId).map(_.map(owner => infoOf(row, owner.kind)))

      def questionsOf(id: SurveyId): Task[List[QuestionInfo]] = survey(id).flatMap:
        case None =>
          ZIO.succeed(Nil)
        case Some(owner) =>
          run(query[QuestionRow].filter(_.surveyId == lift(id))).map(_.map(infoOf(_, owner.kind)))

      def answersTo(id: QuestionId): Task[Int] = run(
        query[AnswerRow].filter(_.questionId == lift(id)).size).map(_.toInt)

      def activeSurveyOf(kind: SurveyKind): Task[Option[SurveyId]] = run(
        query[SurveyRow].filter(s => s.category == lift(categoryOf(kind)) && s.isActive == 1)).map(
        _.headOption.map(_.id))

      def videoOwner(id: String): Task[Option[VideoOwner]] = run(
        query[AdvertiserVideoRow].filter(v => sql"${v.id}".as[String] == lift(id))).map(
        _.headOption.map(video => VideoOwner(video.locationId, video.advertiserId)))
    end new
  end apply

  private def infoOf(row: QuestionRow, kind: SurveyKind) = QuestionInfo(
    row.id,
    row.surveyId,
    kind,
    row.isActive == 1,
    row.displayOrder)

  /** The kind a stored category is, if any. */
  def kindOf(category: String): Option[SurveyKind] =
    category match
      case SurveyCategory.Email =>
        Some(SurveyKind.Email)
      case SurveyCategory.Profiling =>
        Some(SurveyKind.Profiling)
      case SurveyCategory.Location =>
        Some(SurveyKind.Location)
      case SurveyCategory.Advertiser =>
        Some(SurveyKind.Advertiser)
      case _ =>
        None

  /** The category a kind is stored as. */
  def categoryOf(kind: SurveyKind): String =
    kind match
      case SurveyKind.Email =>
        SurveyCategory.Email
      case SurveyKind.Profiling =>
        SurveyCategory.Profiling
      case SurveyKind.Location =>
        SurveyCategory.Location
      case SurveyKind.Advertiser =>
        SurveyCategory.Advertiser
end SurveyAuthoringRepositoryQuill
