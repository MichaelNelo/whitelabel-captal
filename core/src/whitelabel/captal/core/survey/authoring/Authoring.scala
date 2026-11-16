package whitelabel.captal.core.survey.authoring

import java.time.{Instant, LocalDate}

import whitelabel.captal.core.survey.Id as SurveyId
import whitelabel.captal.core.survey.question.{
  HierarchyLevel,
  Id as QuestionId,
  LocalizedText,
  OptionId,
  RangeRule,
  SelectionRule,
  TextRule
}

/** What a survey is asked for: the three identification surveys, asked of every guest of every
  * location, one active of each; and an advertiser's, which follows a video and so a location.
  */
enum SurveyKind(val identification: Boolean):
  case Email      extends SurveyKind(true)
  case Profiling  extends SurveyKind(true)
  case Location   extends SurveyKind(true)
  case Advertiser extends SurveyKind(false)

/** An option of a question, with its text in each locale. */
final case class OptionSpec(id: OptionId, text: List[LocalizedText])

/** What a question asks for, as it is written. */
enum AnswerSpec:
  case Radio(options: List[OptionSpec])
  case Dropdown(options: List[OptionSpec])
  case Checkbox(options: List[OptionSpec], rules: List[SelectionRule])
  case Input(rules: List[TextRule])
  case Numeric(rules: List[RangeRule[BigDecimal]])
  case Date(rules: List[RangeRule[LocalDate]])

  /** The options it is chosen from: none for a question answered otherwise. */
  def choices: List[OptionSpec] =
    this match
      case Radio(options) =>
        options
      case Dropdown(options) =>
        options
      case Checkbox(options, _) =>
        options
      case _ =>
        Nil

  /** Whether it is asked through options, which it then needs at least one of. */
  def choosesFromOptions: Boolean =
    this match
      case Radio(_) | Dropdown(_) | Checkbox(_, _) =>
        true
      case _ =>
        false
end AnswerSpec

/** A question as it is written: its id, its wording, what it asks for, and how it counts. */
final case class QuestionSpec(
    id: QuestionId,
    text: List[LocalizedText],
    answer: AnswerSpec,
    description: List[LocalizedText],
    placeholder: List[LocalizedText],
    required: Boolean,
    points: Int,
    hierarchyLevel: Option[HierarchyLevel])

/** Which of a question's texts a problem is in. */
enum TextField:
  case Text,
    Description,
    Placeholder
  case OptionText(index: Int)

object QuestionSpec:
  /** Every reason `spec` cannot be asked in a survey of `kind`: a question with no wording, a text
    * with a blank locale or value or repeating a locale, a question chosen from options with none
    * or with an option with no text, a hierarchy level outside a location survey, and negative
    * points.
    */
  def problems(spec: QuestionSpec, kind: SurveyKind): List[Error] =
    def texts(field: TextField, written: List[LocalizedText]) = textProblems(field, written)
    val options = spec
      .answer
      .choices
      .zipWithIndex
      .flatMap: (option, index) =>
        if option.text.isEmpty then
          List(Error.OptionWithoutText(index + 1))
        else
          texts(TextField.OptionText(index + 1), option.text)
    List(
      Option.when(spec.text.isEmpty)(Error.NoText).toList,
      texts(TextField.Text, spec.text),
      texts(TextField.Description, spec.description),
      texts(TextField.Placeholder, spec.placeholder),
      Option
        .when(spec.answer.choosesFromOptions && spec.answer.choices.isEmpty)(Error.NoOptions)
        .toList,
      options,
      Option
        .when(spec.hierarchyLevel.isDefined && kind != SurveyKind.Location)(
          Error.HierarchyOutsideLocation)
        .toList,
      Option.when(spec.points < 0)(Error.NegativePoints(spec.points)).toList
    ).flatten
  end problems

  /** Every reason the texts written in `field` cannot be shown: an entry with a blank locale or
    * value, and a locale given twice.
    */
  def textProblems(field: TextField, written: List[LocalizedText]): List[Error] =
    val repeated = written.groupBy(_.locale).filter(_._2.sizeIs > 1).keys.toList.sorted
    List(
      Option.when(written.exists(t => t.locale.isBlank || t.value.isBlank))(Error.BlankText(field)),
      Option.when(repeated.nonEmpty)(Error.RepeatedLocales(field, repeated))
    ).flatten
end QuestionSpec

enum Event:
  /** A survey of `kind`: an identification one, deployment-wide, or the survey asked after the
    * video `video`, of the location and the advertiser the video is.
    */
  case SurveyCreated(
      id: SurveyId,
      kind: SurveyKind,
      video: Option[String],
      location: Option[String],
      advertiser: Option[String],
      name: Option[String],
      occurredAt: Instant)

  /** `question` is asked in the survey `survey`, at `displayOrder`. */
  case QuestionAdded(
      survey: SurveyId,
      question: QuestionSpec,
      displayOrder: Int,
      occurredAt: Instant)

  /** The question is corrected in place, so the answers given to it stay attached: what is given
    * replaces what it had, in the locales given; the rest is kept.
    */
  case QuestionUpdated(
      question: QuestionId,
      text: Option[List[LocalizedText]],
      description: Option[List[LocalizedText]],
      placeholder: Option[List[LocalizedText]],
      required: Option[Boolean],
      points: Option[Int],
      occurredAt: Instant)

  /** No longer asked; its answers are kept. */
  case QuestionRetired(question: QuestionId, occurredAt: Instant)
  case QuestionReinstated(question: QuestionId, occurredAt: Instant)

  /** The survey asks its active questions in `order`. */
  case QuestionsReordered(survey: SurveyId, order: List[QuestionId], occurredAt: Instant)
end Event

enum Error:
  case SurveyNotFound(id: String)
  case QuestionNotFound(id: String)
  case VideoNotFound(id: String)

  /** An active survey of the identification `kind` exists already, `existing`: they are picked by
    * kind alone, so two would be ambiguous.
    */
  case IdentificationSurveyExists(kind: SurveyKind, existing: SurveyId)

  /** The survey, of `kind`, is on the other side of the line between the deployment-wide
    * identification surveys and a location's: each side has its own tools and scope.
    */
  case OtherSide(kind: SurveyKind)

  case NoText
  case BlankText(field: TextField)
  case RepeatedLocales(field: TextField, locales: List[String])
  case NoOptions
  case OptionWithoutText(index: Int)
  case HierarchyOutsideLocation
  case NegativePoints(points: Int)

  /** A reorder that is not every active question exactly once. */
  case NotEveryQuestion(missing: List[String], extra: List[String])
  case RepeatedQuestion

  /** A reorder of a survey with no questions to order. */
  case NoQuestions(survey: String)
end Error
