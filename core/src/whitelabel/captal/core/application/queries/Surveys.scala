package whitelabel.captal.core.application.queries

import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.survey.question.LocalizedText

/** The surveys, retired ones only with `includeInactive`; with `slug`, those of that location and
  * the ones that belong to none, which every location asks.
  */
final case class ListSurveysQuery(slug: Option[Slug], includeInactive: Boolean)

final case class QuestionSummaryReport(id: String, active: Boolean, text: List[LocalizedText])

final case class SurveySummaryReport(
    id: String,
    category: String,
    name: Option[String],
    videoId: Option[String],
    locationSlug: Option[String],
    active: Boolean,
    questions: List[QuestionSummaryReport])

/** The survey `id`, whole. */
final case class SurveyOfQuery(id: String)

final case class OptionReport(
    id: String,
    displayOrder: Int,
    active: Boolean,
    text: List[LocalizedText])

/** A question with the answers given to it so far, and its rules as they are stored: a rule that
  * does not match its type shows here though it never applies.
  */
final case class QuestionReport(
    id: String,
    displayOrder: Int,
    required: Boolean,
    points: Int,
    active: Boolean,
    answers: Int,
    text: List[LocalizedText],
    options: List[OptionReport],
    rules: List[String])

final case class SurveyReport(
    id: String,
    category: String,
    name: Option[String],
    active: Boolean,
    questions: List[QuestionReport])
