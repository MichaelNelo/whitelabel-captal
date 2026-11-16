package whitelabel.captal.mcp.tools

import java.time.LocalDate

import cats.data.NonEmptyChain
import cats.syntax.traverse.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.commands.{
  AddQuestionCommand,
  AnswersKept,
  CreateSurveyCommand,
  CreatedSurvey,
  QuestionActivity as QuestionActivityResult,
  QuestionPlaced,
  ReinstateQuestionCommand,
  ReorderQuestionsCommand,
  RetireQuestionCommand,
  SurveyOwner,
  UpdateQuestionCommand
}
import whitelabel.captal.core.application.queries.{
  ListSurveysQuery,
  QuestionReport,
  SurveyOfQuery,
  SurveyReport,
  SurveySummaryReport
}
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.authoring.{
  AnswerSpec,
  Error as AuthoringError,
  OptionSpec,
  QuestionSpec,
  SurveyKind
}
import whitelabel.captal.core.survey.question
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.queries.Surveys
import whitelabel.captal.platform.repositories.SurveyAuthoringRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{OperatorContext, SurveyCategory as StoredCategory}
import zio.*
import zio.schema.annotation.{description, discriminatorName, rejectExtraFields}
import zio.schema.{DeriveSchema, Schema}

import Adapters.{reads, runs, runsEither}
import McpSchema.given
import Refusals.SurveySide
import ToolSchema.readOnly

// The case names are the wire values, lowercase and underscored, which is why they break Scala
// naming convention. `@caseName` cannot be used instead: JsonSchemaGen builds the enum from the
// case id, so the schema would advertise a name the decoder rejects.

enum SurveyCategory:
  case email
  case profiling
  case location
  case advertiser

object SurveyCategory:
  given Schema[SurveyCategory] = DeriveSchema.gen

  extension (c: SurveyCategory)
    /** The value `surveys.category` holds, by way of [[StoredCategory]] rather than `toString`, so
      * a divergence between the wire word and the stored one is a compile error.
      */
    def stored: String =
      c match
        case SurveyCategory.email =>
          StoredCategory.Email
        case SurveyCategory.profiling =>
          StoredCategory.Profiling
        case SurveyCategory.location =>
          StoredCategory.Location
        case SurveyCategory.advertiser =>
          StoredCategory.Advertiser
end SurveyCategory

/** The three surveys that exist once for the whole deployment rather than once per location. A type
  * of its own so `create_identification_survey` cannot be handed `advertiser`.
  */
enum IdentificationCategory:
  case email
  case profiling
  case location

object IdentificationCategory:
  given Schema[IdentificationCategory] = DeriveSchema.gen

  extension (c: IdentificationCategory)
    /** The value `surveys.category` holds, as in [[SurveyCategory.stored]]. */
    def stored: String =
      c match
        case IdentificationCategory.email =>
          StoredCategory.Email
        case IdentificationCategory.profiling =>
          StoredCategory.Profiling
        case IdentificationCategory.location =>
          StoredCategory.Location

    /** The kind of survey it names in `core`. */
    def kind: SurveyKind =
      c match
        case IdentificationCategory.email =>
          SurveyKind.Email
        case IdentificationCategory.profiling =>
          SurveyKind.Profiling
        case IdentificationCategory.location =>
          SurveyKind.Location
  end extension
end IdentificationCategory

enum Hierarchy:
  case state
  case city
  case municipality
  case urbanization

object Hierarchy:
  given Schema[Hierarchy] = DeriveSchema.gen

  extension (h: Hierarchy)
    /** The level it names in `core`. */
    def level: question.HierarchyLevel =
      h match
        case Hierarchy.state =>
          question.HierarchyLevel.State
        case Hierarchy.city =>
          question.HierarchyLevel.City
        case Hierarchy.municipality =>
          question.HierarchyLevel.Municipality
        case Hierarchy.urbanization =>
          question.HierarchyLevel.Urbanization

enum TextRuleKind:
  case min_length
  case max_length
  case pattern
  case email
  case url

object TextRuleKind:
  given Schema[TextRuleKind] = DeriveSchema.gen

enum SelectionRuleKind:
  case min_selections
  case max_selections

object SelectionRuleKind:
  given Schema[SelectionRuleKind] = DeriveSchema.gen

enum RangeRuleKind:
  case min
  case max

object RangeRuleKind:
  given Schema[RangeRuleKind] = DeriveSchema.gen

final case class LocalizedText(
    @description("BCP 47 tag — 'es', 'en'. One entry per locale the location serves.")
    locale: String,
    @description("The text guests see in that locale.")
    value: String)

object LocalizedText:
  /** The texts as `core` holds them. */
  def toCore(texts: List[LocalizedText]): List[question.LocalizedText] = texts.map(t =>
    question.LocalizedText(t.value, t.locale))

  /** The texts as the tools show them. */
  def fromCore(texts: List[question.LocalizedText]): List[LocalizedText] = texts.map(t =>
    LocalizedText(t.locale, t.value))

final case class AnswerOption(
    @description("The option's label, one entry per locale.")
    text: List[LocalizedText])

final case class TextRule(
    @description("Which rule. Only the kinds an input question accepts are listed here.")
    kind: TextRuleKind,
    @description(
      "Omit for email and url. A whole number for the length rules; a regex for pattern.")
    value: Option[String])
final case class SelectionRule(
    @description("Which bound to set on how many options may be picked.")
    kind: SelectionRuleKind,
    @description("A whole number.")
    value: String)
final case class RangeRule(
    @description("Which end of the range to set.")
    kind: RangeRuleKind,
    @description("Any number for numeric, an ISO date (yyyy-mm-dd) for date.")
    value: String)

/** What varies by question type, and only that. Discriminated so the schema carries the constraint:
  * an `input` variant has no `options` field to pass, and its rules are text rules by type. The
  * fields every question shares stay flat in [[SurveyTools.CreateQuestionRequest]].
  *
  * `ToolSchema.withDiscriminator` has to put the discriminator back into the generated schema,
  * since JsonSchemaGen drops it and the result would describe a payload the decoder rejects.
  */
@discriminatorName("kind")
enum AnswerFormat:
  @rejectExtraFields
  case Radio(
      @description("The choices, in the order they are shown. At least one.")
      options: List[AnswerOption])
  @rejectExtraFields
  case Dropdown(
      @description("The choices, in the order they are shown. At least one.")
      options: List[AnswerOption])
  @rejectExtraFields
  case Checkbox(
      @description("The choices, in the order they are shown. At least one.")
      options: List[AnswerOption],
      @description(
        "How many may be picked: min_selections, max_selections. Omit for no limit either way.")
      rules: Option[List[SelectionRule]])
  @rejectExtraFields
  case Input(
      @description(
        "What the text must satisfy: min_length, max_length, pattern, email, url. Omit for none.")
      rules: Option[List[TextRule]])
  // No `rating`: `QuestionType.Rating` exists in core but the portal renders no input for it. It
  // stays in core, which is the wire contract, so a stored row of that type still decodes; what is
  // closed here is the way to make a new one.
  @rejectExtraFields
  case Numeric(
      @description("The accepted range: min, max. Any number. Omit for unbounded.")
      rules: Option[List[RangeRule]])
  @rejectExtraFields
  case Date(
      @description("The accepted range: min, max, as ISO dates (yyyy-mm-dd). Omit for unbounded.")
      rules: Option[List[RangeRule]])
end AnswerFormat

object AnswerFormat:
  given schema: Schema[AnswerFormat] = DeriveSchema.gen

  /** Read off the derived schema rather than transcribed. `withDiscriminator` zips these
    * positionally against the emitted `oneOf`, so both orders have to come from one derivation.
    */
  val caseIds: List[String] =
    schema match
      case e: Schema.Enum[?] =>
        e.cases.map(_.id).toList
      case other =>
        throw new IllegalStateException(
          s"AnswerFormat no longer derives to an enum schema but to $other; the discriminator patch has nothing to attach to.")

/** Survey and question tools, through `core`'s survey authoring commands and the `ListSurveys` and
  * `SurveyOf` queries. What is left here is the wire: the request shapes, and reading a rule's
  * value, which arrives as text, into the rule it names.
  *
  * Nothing here deletes: `answers.question_id` references `questions`, so retiring is the only safe
  * removal.
  */
object SurveyTools:
  final case class OptionView(
      @description("Stable across edits; answers reference it.")
      id: String,
      @description("Position within the question, ascending.")
      displayOrder: Int,
      @description("A retired option stops being offered but keeps the answers that chose it.")
      active: Boolean,
      @description("The label, per locale. A locale missing here shows nothing for this option.")
      text: List[LocalizedText])
  final case class QuestionView(
      @description("What set_question_active and reorder_questions take.")
      id: String,
      @description("Position within the survey. Questions are served in this order, not at random.")
      displayOrder: Int,
      @description("Whether a guest must answer to move on.")
      required: Boolean,
      @description("Points awarded for answering.")
      points: Int,
      @description("A retired question stops being served but keeps its answers.")
      active: Boolean,
      @description(
        "How many answers already reference this question. Non-zero is why retiring exists instead of deleting: the rows would lose what they point at.")
      answers: Int,
      @description("The wording, per locale.")
      text: List[LocalizedText],
      @description("Empty for the types that take no options.")
      options: List[OptionView],
      @description(
        "The validation rules exactly as stored, one JSON object each — '{\"MinLength\":{\"min\":3}}'. Raw and not decoded on purpose: a rule that does not match its question type is dropped when the portal reads it, so this is the only view that shows one which exists in the table but never applies.")
      rules: List[String])
  final case class SurveyView(
      @description("What create_question takes to add to this survey.")
      id: String,
      @description(
        "What the survey is for: identification surveys have no location, the rest belong to one.")
      category: String,
      @description("Operator-facing label. Absent when none was set.")
      name: Option[String],
      @description("A retired survey is served to nobody, whatever its questions say.")
      active: Boolean,
      @description("In display order, retired questions included so the numbering makes sense.")
      questions: List[QuestionView])

  final case class ListRequest(
      @description("Retired surveys are hidden by default; set this to see them too.")
      includeInactive: Boolean = false,
      @description(
        "Show only the surveys of one location. Omit for all of them. Email, profiling and location surveys belong to no location and are always shown, since the portal picks them by category across the whole deployment.")
      slug: Option[String] = None)

  /** Enough to find a question and act on it. `get_survey` is where the options, the rules and the
    * answer count live.
    */
  final case class QuestionSummary(
      @description("What set_question_active and reorder_questions take.")
      id: String,
      @description("A retired question stops being served but keeps its answers.")
      active: Boolean,
      @description("The wording, per locale. This is what an operator recognises a question by.")
      text: List[LocalizedText])

  final case class SurveySummary(
      @description("What create_question and get_survey take.")
      id: String,
      @description("What the survey is for.")
      category: String,
      @description("Operator-facing label. Absent when none was set.")
      name: Option[String],
      @description(
        "Present only on advertiser surveys: the video this one follows. It is the link to the video, where the name is only a description of it.")
      videoId: Option[String],
      @description(
        "Where this survey is served. Absent means everywhere — email, profiling and location surveys are chosen by category alone, with no location filter in the read path.")
      locationSlug: Option[String],
      @description("A retired survey is served to nobody, whatever its questions say.")
      active: Boolean,
      @description(
        "In display order, retired ones included. An empty list on an active survey is one that would be served with nothing to ask.")
      questions: List[QuestionSummary])

  final case class ListResponse(
      @description("Call get_survey with an id for a question's options, rules and answer count.")
      surveys: List[SurveySummary])

  final case class GetRequest(
      @description("Which survey to read. Call list_surveys for the ids.")
      surveyId: String)

  final case class SetQuestionActiveRequest(
      @description("Which question to retire or bring back.")
      questionId: String,
      @description(
        "False retires it. This is the only removal there is: answers reference the question, so deleting would strand them.")
      active: Boolean)
  final case class SetQuestionActiveResponse(
      @description("The question changed.")
      questionId: String,
      @description("Its state after the change.")
      active: Boolean,
      @description("Answers still referencing it. Retiring loses none of them.")
      answersPreserved: Int,
      @description(
        "Questions left in rotation in this survey. Zero means the survey would now be served with nothing to ask.")
      remainingActive: Int)

  final case class ReorderRequest(
      @description("Which survey to reorder.")
      surveyId: String,
      @description(
        "Every active question of the survey, in the order they should be served. A partial list is refused rather than applied to the ids it names.")
      orderedQuestionIds: List[String])
  final case class ReorderResponse(
      @description("The survey reordered.")
      surveyId: String,
      @description("The order as stored, which should match what was sent.")
      order: List[String])

  given Schema[LocalizedText] = DeriveSchema.gen
  given Schema[OptionView] = DeriveSchema.gen
  given Schema[QuestionView] = DeriveSchema.gen
  given Schema[SurveyView] = DeriveSchema.gen
  given Schema[ListRequest] = DeriveSchema.gen
  given Schema[QuestionSummary] = DeriveSchema.gen
  given Schema[SurveySummary] = DeriveSchema.gen
  given Schema[ListResponse] = DeriveSchema.gen
  given Schema[GetRequest] = DeriveSchema.gen

  /** What a question can be corrected to after it exists. Not its type and not its options: retire
    * and create instead.
    *
    * Every field is optional and an omitted one is left alone.
    */
  final case class UpdateQuestionRequest(
      @description("Which question. Call get_survey for the ids in a survey.")
      questionId: String,
      @description(
        "The corrected wording, one entry per locale. Only the locales given are rewritten; the others are left as they are. Ask the operator for it — never invent copy a guest will read.")
      text: Option[List[LocalizedText]],
      @description("Help text under the question. Same per-locale rules as `text`.")
      description: Option[List[LocalizedText]],
      @description(
        "Placeholder inside the input. Same per-locale rules as `text`. Only means anything for the types that take free input.")
      placeholder: Option[List[LocalizedText]],
      @description("Whether an answer is mandatory.")
      required: Option[Boolean],
      @description("Points awarded for answering.")
      points: Option[Int])

  final case class UpdateQuestionResponse(
      @description("The question changed.")
      questionId: String,
      @description(
        "Which fields this call actually wrote. Empty means the request asked for nothing.")
      updated: List[String],
      @description(
        "Answers already recorded against it. Editing the wording does not touch them, which is worth knowing before rewording: the answers were given to the old text.")
      answersPreserved: Int)

  given Schema[UpdateQuestionRequest] = DeriveSchema.gen
  given Schema[UpdateQuestionResponse] = DeriveSchema.gen
  given Schema[SetQuestionActiveRequest] = DeriveSchema.gen
  given Schema[SetQuestionActiveResponse] = DeriveSchema.gen
  given Schema[ReorderRequest] = DeriveSchema.gen
  given Schema[ReorderResponse] = DeriveSchema.gen

  private def refused(error: AuthoringError): String = Refusals.describe(
    AppError.SurveyAuthoring(NonEmptyChain.one(error)))

  private def surveyIdOf(raw: String): IO[String, survey.Id] = ZIO
    .fromOption(survey.Id.fromString(raw.trim))
    .orElseFail(refused(AuthoringError.SurveyNotFound(raw)))

  private def questionIdOf(raw: String): IO[String, question.Id] = ZIO
    .fromOption(question.Id.fromString(raw.trim))
    .orElseFail(refused(AuthoringError.QuestionNotFound(raw)))

  /** How a refusal of one of a pair reads: naming, for a survey on the other side, the other tool.
    */
  private def describedFor(side: SurveySide): AppError => String = Refusals.describing(Some(side))

  val list: McpToolHandlerR[Queries.ListSurveys] =
    McpTool("list_surveys")
      .description(
        "Every survey with the questions in it, enough of each to find one and act on it. Categories are email, profiling, location and advertiser; only advertiser surveys belong to a location, through their video. Call get_survey for a question's options, rules and answer count.")
      .readOnly
      .reads((in: ListRequest) =>
        ZIO.foreach(in.slug)(Refusals.slugOf).map(ListSurveysQuery(_, in.includeInactive))):
        (_, reports: List[SurveySummaryReport]) =>
          ZIO.succeed(
            ListResponse(
              reports.map: s =>
                SurveySummary(
                  id = s.id,
                  category = s.category,
                  name = s.name,
                  videoId = s.videoId,
                  locationSlug = s.locationSlug,
                  active = s.active,
                  questions = s
                    .questions
                    .map(q => QuestionSummary(q.id, q.active, LocalizedText.fromCore(q.text)))
                )))

  /** The read behind the `captal://surveys/{id}` resource, which `get_survey` answers too. */
  private[mcp] def viewOf(surveyId: String): ZIO[QuillSqlite, String, SurveyView] = ZIO
    .serviceWithZIO[QuillSqlite](quill =>
      Commands.ask(Surveys.of(quill), SurveyOfQuery(surveyId))())
    .map(viewOfReport)

  private def viewOfReport(report: SurveyReport): SurveyView =
    def questionView(q: QuestionReport) = QuestionView(
      id = q.id,
      displayOrder = q.displayOrder,
      required = q.required,
      points = q.points,
      active = q.active,
      answers = q.answers,
      text = LocalizedText.fromCore(q.text),
      options = q
        .options
        .map(o => OptionView(o.id, o.displayOrder, o.active, LocalizedText.fromCore(o.text))),
      rules = q.rules
    )
    SurveyView(
      report.id,
      report.category,
      report.name,
      report.active,
      report.questions.map(questionView))
  end viewOfReport

  val get: McpToolHandlerR[Queries.SurveyOf] =
    McpTool("get_survey")
      .description(
        "The full contents of a survey: every question in display order with its texts, options, validation rules and how many answers reference it. Retired questions and options are included, marked inactive.")
      .readOnly
      .reads((in: GetRequest) => ZIO.succeed(SurveyOfQuery(in.surveyId)))(
        (_, report: SurveyReport) => ZIO.succeed(viewOfReport(report)))

  /** Corrects a question that is already being asked, in place, so `answers.question_id` keeps
    * pointing at it. Answers recorded before the edit were given to the old wording, which the
    * response reports.
    *
    * Paired by scope like the rest: `identity:write` for the deployment-wide surveys,
    * `content:write` for a video's.
    */
  private def update(
      side: SurveySide): McpToolHandlerR[OperatorContext & ContentFlows.UpdateQuestion] =
    McpTool(
      if side.identification then
        "update_identification_question"
      else
        "update_question")
      .description(
        if side.identification then
          "Correct a question in one of the three deployment-wide identification surveys: its wording, help text, placeholder, whether it is required, or its points. Every guest of every location is asked these. Omitted fields are left alone. Cannot change the question's type or its options — retire it and create a new one for that."
        else
          "Correct a question in a video's survey: its wording, help text, placeholder, whether it is required, or its points. Omitted fields are left alone. Cannot change the question's type or its options — retire it and create a new one for that. Answers already given are kept and still point at this question, so they were answers to the old wording.")
      .requireScopes(
        if side.identification then
          Auth.IdentityWrite
        else
          Auth.ContentWrite)
      .runs(
        (in: UpdateQuestionRequest) =>
          for
            id  <- questionIdOf(in.questionId)
            now <- Clock.instant
          yield UpdateQuestionCommand(
            id,
            side.identification,
            in.text.map(LocalizedText.toCore),
            in.description.map(LocalizedText.toCore),
            in.placeholder.map(LocalizedText.toCore),
            in.required,
            in.points,
            now
          ),
        describedFor(side)
      ): (in, kept: AnswersKept) =>
        val written =
          List(
            in.text.map(_ => "text"),
            in.description.map(_ => "description"),
            in.placeholder.map(_ => "placeholder"),
            in.required.map(_ => "required"),
            in.points.map(_ => "points")
          ).flatten
        questionIdOf(in.questionId).map(id =>
          UpdateQuestionResponse(id.asString, written, kept.answers))

  private val identification = SurveySide(identification = true, otherTool = "")

  val updateQuestion: McpToolHandlerR[OperatorContext & ContentFlows.UpdateQuestion] = update(
    SurveySide(identification = false, otherTool = "update_identification_question"))

  val updateIdentificationQuestion: McpToolHandlerR[OperatorContext & ContentFlows.UpdateQuestion] =
    update(identification.copy(otherTool = "update_question"))

  private type QuestionActivity = ContentFlows.RetireQuestion & ContentFlows.ReinstateQuestion

  private def setActive(side: SurveySide): McpToolHandlerR[OperatorContext & QuestionActivity] =
    McpTool(
      if side.identification then
        "set_identification_question_active"
      else
        "set_question_active")
      .description(
        if side.identification then
          "Retire a question from one of the three deployment-wide identification surveys, or bring it back. Every guest of every location is asked these, so this is not a per-location change. Never deletes: answers reference questions, so a retired one keeps resolving for everything already recorded."
        else
          "Retire a question from an advertiser's survey, or bring it back. Never deletes: answers reference questions, so a retired question keeps resolving for everything already recorded. Reports how many answers that is.")
      .requireScopes(
        if side.identification then
          Auth.IdentityWrite
        else
          Auth.ContentWrite)
      .runsEither(
        (in: SetQuestionActiveRequest) =>
          for
            id  <- questionIdOf(in.questionId)
            now <- Clock.instant
          yield
            if in.active then
              Right(ReinstateQuestionCommand(id, side.identification, now))
            else
              Left(RetireQuestionCommand(id, side.identification, now))
        ,
        describedFor(side)
      ): (in, activity: QuestionActivityResult) =>
        questionIdOf(in.questionId).map(id =>
          SetQuestionActiveResponse(
            id.asString,
            in.active,
            activity.answers,
            activity.remainingActive))

  val setQuestionActive: McpToolHandlerR[OperatorContext & QuestionActivity] = setActive(
    SurveySide(identification = false, otherTool = "set_identification_question_active"))

  val setIdentificationQuestionActive: McpToolHandlerR[OperatorContext & QuestionActivity] =
    setActive(identification.copy(otherTool = "set_question_active"))

  private def reorderQuestions(
      side: SurveySide): McpToolHandlerR[OperatorContext & ContentFlows.ReorderQuestions] =
    McpTool(
      if side.identification then
        "reorder_identification_questions"
      else
        "reorder_questions")
      .description(
        if side.identification then
          "Set the order the questions of a deployment-wide identification survey are asked in. Give every active question of the survey exactly once; display_order is rewritten to match. This is the order every guest of every location sees."
        else
          "Set the order an advertiser survey's questions are asked in. Give every active question of the survey exactly once; display_order is rewritten to match the list.")
      .requireScopes(
        if side.identification then
          Auth.IdentityWrite
        else
          Auth.ContentWrite)
      .runs(
        (in: ReorderRequest) =>
          for
            id  <- surveyIdOf(in.surveyId)
            now <- Clock.instant
          yield ReorderQuestionsCommand(id, side.identification, in.orderedQuestionIds, now),
        describedFor(side)
      )((in, _: Unit) => ZIO.succeed(ReorderResponse(in.surveyId, in.orderedQuestionIds)))

  val reorder: McpToolHandlerR[OperatorContext & ContentFlows.ReorderQuestions] = reorderQuestions(
    SurveySide(identification = false, otherTool = "reorder_identification_questions"))

  val reorderIdentification: McpToolHandlerR[OperatorContext & ContentFlows.ReorderQuestions] =
    reorderQuestions(identification.copy(otherTool = "reorder_questions"))

  // ───────────────────────────────────────────────────────────────────────────
  // Creation
  //
  // Anything with a default is an `Option`: zio-schema marks only non-Option fields as required,
  // and a Scala default does not reach the schema. Every constrained field is an enum, so the legal
  // values are in the schema rather than in prose.
  // ───────────────────────────────────────────────────────────────────────────

  final case class CreateSurveyRequest(
      @description(
        "The video this survey follows. Required: it is what findNextAdvertiserSurvey matches on, and the survey inherits that video's location.")
      videoId: String,
      @description("Label for operators. Not shown to guests; safe to omit.")
      name: Option[String])

  /** Its own request type rather than a category on the one above, so the schema refuses
    * `advertiser` outright.
    */
  final case class CreateIdentificationSurveyRequest(
      @description(
        "Which of the three identification surveys this is. There is one of each for the whole deployment.")
      category: IdentificationCategory,
      @description("Label for operators. Not shown to guests; safe to omit.")
      name: Option[String])
  final case class CreateSurveyResponse(
      @description(
        "Pass to create_question to add questions. A survey with none is served to nobody.")
      surveyId: String,
      @description("The category as stored.")
      category: String)

  final case class CreateQuestionRequest(
      @description("Which survey to append to. Call list_surveys for the ids.")
      surveyId: String,
      @description(
        "The wording, one entry per locale. Ask the operator for this; nothing else can supply it.")
      text: List[LocalizedText],
      @description(
        "How the answer is collected, and what constrains it. `kind` picks the variant and decides which other fields are allowed; a field the variant does not have is refused.")
      answer: AnswerFormat,
      @description("Helper text under the question. Omit when there is none.")
      description: Option[List[LocalizedText]],
      @description("Placeholder inside the input. Only meaningful for input questions.")
      placeholder: Option[List[LocalizedText]],
      @description("Whether an answer is mandatory. Defaults to true.")
      required: Option[Boolean],
      @description("Points awarded for answering. Defaults to 10, which is what every survey uses.")
      points: Option[Int],
      @description("Only for questions in a location survey; rejected elsewhere.")
      hierarchyLevel: Option[Hierarchy])
  final case class CreateQuestionResponse(
      @description("What set_question_active and reorder_questions take.")
      questionId: String,
      @description(
        "Where it landed: appended after the survey's existing questions. Use reorder_questions to move it.")
      displayOrder: Int,
      @description(
        "Ids of the options created, in the order given. Empty for the types that take none — and empty for a choice type means the options were dropped rather than stored.")
      optionIds: List[String])

  given Schema[AnswerOption] = DeriveSchema.gen
  given Schema[CreateSurveyRequest] = DeriveSchema.gen
  given Schema[CreateIdentificationSurveyRequest] = DeriveSchema.gen
  given Schema[CreateSurveyResponse] = DeriveSchema.gen
  given Schema[CreateQuestionRequest] = DeriveSchema.gen
  given Schema[CreateQuestionResponse] = DeriveSchema.gen

  /** The question a request describes, with fresh ids for it and its options. A rule's value
    * arrives as text and is read here into the rule it names; one that does not read is refused
    * before `core` sees the question.
    */
  private[mcp] def specOf(
      text: List[LocalizedText],
      answer: AnswerFormat,
      description: List[LocalizedText],
      placeholder: List[LocalizedText],
      required: Boolean,
      points: Int,
      hierarchyLevel: Option[Hierarchy]): Either[String, QuestionSpec] = answerOf(answer).map(
    QuestionSpec(
      question.Id.generate,
      LocalizedText.toCore(text),
      _,
      LocalizedText.toCore(description),
      LocalizedText.toCore(placeholder),
      required,
      points,
      hierarchyLevel.map(_.level)
    ))

  private def answerOf(format: AnswerFormat): Either[String, AnswerSpec] =
    def options(listed: List[AnswerOption]) = listed.map(option =>
      OptionSpec(question.OptionId.generate, LocalizedText.toCore(option.text)))
    def read[A, B](rules: Option[List[A]])(rule: A => Either[String, B]) = rules
      .getOrElse(Nil)
      .traverse(rule)
    format match
      case AnswerFormat.Radio(listed) =>
        Right(AnswerSpec.Radio(options(listed)))
      case AnswerFormat.Dropdown(listed) =>
        Right(AnswerSpec.Dropdown(options(listed)))
      case AnswerFormat.Checkbox(listed, rules) =>
        read(rules)(selectionRule).map(AnswerSpec.Checkbox(options(listed), _))
      case AnswerFormat.Input(rules) =>
        read(rules)(textRule).map(AnswerSpec.Input(_))
      case AnswerFormat.Numeric(rules) =>
        read(rules)(rangeRule(_, v => scala.util.Try(BigDecimal(v)).toOption, "a number")).map(
          AnswerSpec.Numeric(_))
      case AnswerFormat.Date(rules) =>
        read(rules)(
          rangeRule(
            _,
            v => scala.util.Try(LocalDate.parse(v)).toOption,
            "an ISO date (yyyy-mm-dd)")).map(AnswerSpec.Date(_))
  end answerOf

  private def textRule(rule: TextRule): Either[String, question.TextRule] =
    def needs[A](parse: String => Option[A], what: String): Either[String, A] = rule
      .value
      .toRight(s"The ${rule.kind} rule needs a value.")
      .flatMap(v => parse(v).toRight(s"The ${rule.kind} rule needs $what, got '$v'."))
    rule.kind match
      case TextRuleKind.min_length =>
        needs(_.toIntOption, "a whole number").map(question.TextRule.MinLength(_))
      case TextRuleKind.max_length =>
        needs(_.toIntOption, "a whole number").map(question.TextRule.MaxLength(_))
      case TextRuleKind.pattern =>
        needs(Some(_), "a regex").map(question.TextRule.Pattern(_))
      case TextRuleKind.email =>
        Right(question.TextRule.Email)
      case TextRuleKind.url =>
        Right(question.TextRule.Url)

  private def selectionRule(rule: SelectionRule): Either[String, question.SelectionRule] = rule
    .value
    .toIntOption
    .toRight(s"The ${rule.kind} rule needs a whole number, got '${rule.value}'.")
    .map: count =>
      rule.kind match
        case SelectionRuleKind.min_selections =>
          question.SelectionRule.MinSelections(count)
        case SelectionRuleKind.max_selections =>
          question.SelectionRule.MaxSelections(count)

  /** `RangeRule` serves both the numeric and the date question, so its bound is text and the
    * question decides what it reads as.
    */
  private def rangeRule[A](
      rule: RangeRule,
      parse: String => Option[A],
      what: String): Either[String, question.RangeRule[A]] = parse(rule.value)
    .toRight(s"The ${rule.kind} rule needs $what, got '${rule.value}'.")
    .map: bound =>
      rule.kind match
        case RangeRuleKind.min =>
          question.RangeRule.Min(bound)
        case RangeRuleKind.max =>
          question.RangeRule.Max(bound)

  /** A survey belonging to a location through the video it follows, an advertiser's or a promo's:
    * it takes the video's location and advertiser, a promo's having none. Identification surveys go
    * through [[createIdentificationSurvey]], behind a different scope.
    */
  val createSurvey: McpToolHandlerR[OperatorContext & ContentFlows.CreateSurvey] =
    McpTool("create_survey")
      .description(
        "Create the survey shown after a video — an advertiser's or one of the venue's own promos. It inherits the video's location and advertiser, so there is nothing to say about where it belongs or whose it is. Call read_skill('add-survey') first.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: CreateSurveyRequest) => creating(SurveyOwner.Video(in.videoId), in.name))(
        (_, survey: CreatedSurvey) => ZIO.succeed(createdView(survey)))

  /** Behind [[Auth.IdentityWrite]] rather than `content:write`: these three are asked of every
    * guest of every location, so creating one changes the identification flow for the whole
    * deployment.
    */
  val createIdentificationSurvey: McpToolHandlerR[OperatorContext & ContentFlows.CreateSurvey] =
    McpTool("create_identification_survey")
      .description(
        "Create one of the three deployment-wide surveys asked before a guest is identified: email, profiling or location. Not scoped to any location — there is one of each for every portal at once, and creating a second of the same kind is refused. Call read_skill('add-survey') first.")
      .requireScopes(Auth.IdentityWrite)
      .runs((in: CreateIdentificationSurveyRequest) =>
        creating(SurveyOwner.Identification(in.category.kind), in.name))(
        (_, survey: CreatedSurvey) => ZIO.succeed(createdView(survey)))

  private def creating(owner: SurveyOwner, name: Option[String]): UIO[CreateSurveyCommand] = Clock
    .instant
    .map(CreateSurveyCommand(survey.Id.generate, owner, name, _))

  private def createdView(created: CreatedSurvey) = CreateSurveyResponse(
    created.id.asString,
    SurveyAuthoringRepositoryQuill.categoryOf(created.kind))

  private def addQuestion(
      side: SurveySide): McpToolHandlerR[OperatorContext & ContentFlows.AddQuestion] =
    McpTool(
      if side.identification then
        "create_identification_question"
      else
        "create_question")
      .description(
        if side.identification then
          "Add a question to one of the three deployment-wide identification surveys. Every guest of every location answers it before being identified, so this is not a per-location change. Appended last; use reorder_identification_questions to move it. Call read_skill('add-survey') for the locale constraints, which the schema cannot express."
        else
          "Add a question to an advertiser's survey. Appended last; use reorder_questions to move it. Only the wording has to come from the operator — the question shape carries what varies by type, and everything else has a default. Call read_skill('add-survey') for the locale constraints, which the schema cannot express.")
      .requireScopes(
        if side.identification then
          Auth.IdentityWrite
        else
          Auth.ContentWrite)
      .runs(
        (in: CreateQuestionRequest) =>
          for
            id      <- surveyIdOf(in.surveyId)
            written <- ZIO.fromEither(
              specOf(
                in.text,
                in.answer,
                in.description.getOrElse(Nil),
                in.placeholder.getOrElse(Nil),
                in.required.getOrElse(true),
                in.points.getOrElse(10),
                in.hierarchyLevel))
            now <- Clock.instant
          yield AddQuestionCommand(id, side.identification, written, now),
        describedFor(side)
      ): (_, placed: QuestionPlaced) =>
        ZIO.succeed(
          CreateQuestionResponse(
            placed.id.asString,
            placed.displayOrder,
            placed.options.map(_.asString)))

  val createQuestion: McpToolHandlerR[OperatorContext & ContentFlows.AddQuestion] = addQuestion(
    SurveySide(identification = false, otherTool = "create_identification_question"))

  val createIdentificationQuestion: McpToolHandlerR[OperatorContext & ContentFlows.AddQuestion] =
    addQuestion(identification.copy(otherTool = "create_question"))

  /** The question tools come in pairs that differ only in which surveys they accept and which scope
    * they require, so `tools/list` publishes the requirement each one actually enforces.
    */
  val all: List[McpToolHandlerR[
    Queries.ListSurveys & Queries.SurveyOf & OperatorContext & ContentFlows.Surveys]] = List(
    list,
    get,
    setQuestionActive,
    setIdentificationQuestionActive,
    reorder,
    reorderIdentification,
    createSurvey,
    createIdentificationSurvey,
    createQuestion,
    createIdentificationQuestion,
    updateQuestion,
    updateIdentificationQuestion
  )
end SurveyTools
