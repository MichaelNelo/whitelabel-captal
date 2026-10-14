package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.survey
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{
  AdvertiserVideoRow,
  AnswerRow,
  LocalizedTextRow,
  LocationRow,
  QuestionOptionRow,
  QuestionRow,
  QuestionRuleRow,
  SurveyCategory as StoredCategory,
  SurveyRow
}
import zio.*
import zio.schema.annotation.{description, discriminatorName, rejectExtraFields}
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

// The case names are the wire values, lowercase and underscored, which is why they break Scala
// naming convention. `@caseName` is not an option: the JSON codec honours it but JsonSchemaGen
// builds the enum from the case id, so the schema would advertise "Profiling" while the decoder
// rejected anything but "profiling". A schema that lies is worse than an ugly identifier.

enum SurveyCategory:
  case email
  case profiling
  case location
  case advertiser

object SurveyCategory:
  given Schema[SurveyCategory] = DeriveSchema.gen

  extension (c: SurveyCategory)
    /** The stored word, by way of [[StoredCategory]] rather than `toString`.
      *
      * `toString` would give the same four strings today, which is precisely why this exists: the
      * wire value and the stored value agreeing is a fact nobody checked. An exhaustive match
      * against the constants `surveys.category` is actually written with makes a divergence a
      * compile error instead of a survey the portal never finds.
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

/** The three surveys that exist once for the whole deployment rather than once per location.
  *
  * A type of its own so `create_identification_survey` cannot be handed `advertiser`. The two are
  * not variants of one operation: one belongs to a location through its video, the other belongs to
  * nobody, and they are guarded by different scopes for that reason.
  */
enum IdentificationCategory:
  case email
  case profiling
  case location

object IdentificationCategory:
  given Schema[IdentificationCategory] = DeriveSchema.gen

  extension (c: IdentificationCategory)
    /** The stored word, for the reason [[SurveyCategory.stored]] gives. */
    def stored: String =
      c match
        case IdentificationCategory.email =>
          StoredCategory.Email
        case IdentificationCategory.profiling =>
          StoredCategory.Profiling
        case IdentificationCategory.location =>
          StoredCategory.Location

  /** The stored words of the three, which is what `surveys.category` holds. Read by
    * `isIdentification`, which is handed a stored value and not a wire one.
    */
  val storedValues: Set[String] = values.map(_.stored).toSet

enum Hierarchy:
  case state
  case city
  case municipality
  case urbanization

object Hierarchy:
  given Schema[Hierarchy] = DeriveSchema.gen

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

/** What varies by question type, and only that.
  *
  * Discriminated so the schema carries the constraint instead of the prose: an `input` variant has
  * no `options` field to pass, and its rules are text rules by type. The fields every question
  * shares stay flat in [[SurveyTools.CreateQuestionRequest]] rather than being repeated across
  * seven variants.
  *
  * The discriminator is put back into the generated schema by `ToolSchema.withDiscriminator` —
  * JsonSchemaGen drops it, which would leave the schema describing a payload the decoder rejects.
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
  // No `rating`. `QuestionType.Rating` exists in core and the portal does not render it: the view
  // matches the case and emits the literal string "Rating input - TODO", which is what a guest
  // would read. Everything around it is built — codecs, range validation, `RatingOutOfRange`, the
  // `ratingOutOfRange` message — and the one missing piece is the input itself.
  //
  // Removed from this schema rather than from core, which is the wire contract with the portal: a
  // stored row of that type still has to decode. What is closed here is the way to make a new one.
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

  /** Read off the derived schema rather than transcribed, so adding or reordering a case above
    * cannot leave this list disagreeing with it. `withDiscriminator` zips these positionally
    * against the emitted `oneOf`, and both orders come from the same derivation.
    */
  val caseIds: List[String] =
    schema match
      case e: Schema.Enum[?] =>
        e.cases.map(_.id).toList
      case other =>
        throw new IllegalStateException(
          s"AnswerFormat no longer derives to an enum schema but to $other; the discriminator patch has nothing to attach to.")

/** Survey and question tools.
  *
  * The first entity with nested children, and the first where the answer to "can I delete this?" is
  * no. `answers.question_id` references `questions`, so retiring is the only safe removal — which
  * is what V20 added the `is_active` columns for.
  *
  * `surveys.category` and `questions.question_type` carry CHECK constraints, but a CHECK violation
  * is dropped as silently as any other rejected statement here, so the legal values are also
  * checked in Scala to produce a message the operator can act on.
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
      @description("The stored validation rules, rendered for reading — 'min_length=3'.")
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

  /** Enough to find a question and act on it, and no more. `get_survey` is where the options, the
    * rules and the answer count live — duplicating those here would make it the same call twice.
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
        "Every question in the survey, retired ones included, in the order they should be served. A partial list is refused rather than applied to the ids it names.")
      orderedQuestionIds: List[String])
  final case class ReorderResponse(
      @description("The survey reordered.")
      surveyId: String,
      @description("The order as stored, which should match what was sent.")
      order: List[String])

  given Schema[OptionView] = DeriveSchema.gen
  given Schema[QuestionView] = DeriveSchema.gen
  given Schema[SurveyView] = DeriveSchema.gen
  given Schema[ListRequest] = DeriveSchema.gen
  given Schema[QuestionSummary] = DeriveSchema.gen
  given Schema[SurveySummary] = DeriveSchema.gen
  given Schema[ListResponse] = DeriveSchema.gen
  given Schema[GetRequest] = DeriveSchema.gen

  /** What a question can be corrected to after it exists.
    *
    * Not the question's type, and not its options. The type decides which rules and options are
    * even representable, so changing it would invalidate rows hanging off it and the answers
    * already recorded against it — retire and create instead. Options are a list with their own
    * ids, texts and answers, which is a different operation from fixing a sentence.
    *
    * Every field is optional and an omitted one is left alone, so correcting a typo cannot reset
    * the points to a default nobody asked for.
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

  private inline def questionsOfQuery = quote: (surveyIdParam: String) =>
    query[QuestionRow].filter(q => sql"${q.surveyId}".as[String] == surveyIdParam)

  private inline def questionByIdQuery = quote: (idParam: String) =>
    query[QuestionRow].filter(q => sql"${q.id}".as[String] == idParam)

  private inline def answerCountQuery = quote: (questionIdParam: String) =>
    query[AnswerRow].filter(a => sql"${a.questionId}".as[String] == questionIdParam).size

  private inline def videoByIdQuery = quote: (idParam: String) =>
    query[AdvertiserVideoRow].filter(v => sql"${v.id}".as[String] == idParam)

  private inline def textsForQuery = quote: (entityIdParam: String) =>
    query[LocalizedTextRow].filter(t => t.entityId == entityIdParam && t.isActive == 1)

  private def texts(entityId: String): ZIO[QuillSqlite, Nothing, List[LocalizedText]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(textsForQuery(lift(entityId)))
        .orDie
        .map(_.sortBy(_.locale).map(r => LocalizedText(r.locale, r.value)))

  private def answerCount(questionId: String): ZIO[QuillSqlite, Nothing, Int] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(answerCountQuery(lift(questionId))).orDie.map(_.toInt)

  /** Zero is allowed and negative is not, matching `AdvertiserTools.validatePriority`. A question
    * worth no points is a reasonable thing to want; one worth less than nothing is a typo.
    */
  private def validatePoints(points: Option[Int]): ZIO[Any, String, Unit] =
    ZIO
      .fail(s"Points must be zero or greater, got ${points.getOrElse(0)}.")
      .when(points.exists(_ < 0))
      .unit

  private def findQuestion(id: String): ZIO[QuillSqlite, String, QuestionRow] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(questionByIdQuery(lift(id)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No question with id '$id'. Call get_survey to see what a survey contains.")

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_surveys")
    .description(
      "Every survey with the questions in it, enough of each to find one and act on it. Categories are email, profiling, location and advertiser; only advertiser surveys belong to a location, through their video. Call get_survey for a question's options, rules and answer count.")
    .readOnly
    .handle: (in: ListRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          surveys   <- run(query[SurveyRow]).orDie
          questions <- run(query[QuestionRow]).orDie
          locations <- run(query[LocationRow]).orDie
          slugOf = locations.map(l => l.id -> l.slug).toMap
          // A slug that resolves to nothing would otherwise filter everything out and read as "this
          // location has no surveys" rather than "there is no such location".
          _ <-
            ZIO.foreachDiscard(in.slug): wanted =>
              ZIO
                .fail(s"No location with slug '$wanted'. Call list_locations to see what exists.")
                .unless(locations.exists(_.slug == wanted))
          visible = surveys
            .filter(s => in.includeInactive || s.isActive == 1)
            // The unscoped categories are kept whatever the filter says: they are served at every
            // location, so hiding them would misreport what a guest there actually sees.
            .filter(s =>
              in.slug.forall(wanted => s.locationId.forall(id => slugOf.get(id).contains(wanted))))
          summaries <-
            ZIO.foreach(visible.sortBy(s => (s.category, s.name.getOrElse("")))): s =>
              ZIO
                .foreach(questions.filter(_.surveyId == s.id).sortBy(_.displayOrder)): q =>
                  texts(q.id.asString).map(QuestionSummary(q.id.asString, q.isActive == 1, _))
                .map: summaries =>
                  SurveySummary(
                    id = s.id.asString,
                    category = s.category,
                    name = s.name,
                    videoId = s.videoId,
                    locationSlug = s.locationId.flatMap(slugOf.get),
                    active = s.isActive == 1,
                    questions = summaries
                  )
        yield ListResponse(summaries)
        end for

  /** The read behind `get_survey`, lifted out so the `captal://surveys/{id}` resource answers with
    * the same record rather than a second implementation that could drift from it.
    */
  private[mcp] def viewOf(surveyId: String): ZIO[QuillSqlite, String, SurveyView] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        surveys <- run(query[SurveyRow]).orDie
        survey  <- ZIO
          .succeed(surveys.find(_.id.asString == surveyId))
          .someOrFail(s"No survey with id '$surveyId'. Call list_surveys to see what exists.")
        questionRows <- run(questionsOfQuery(lift(surveyId))).orDie
        optionRows   <- run(query[QuestionOptionRow]).orDie
        ruleRows     <- run(query[QuestionRuleRow]).orDie
        views        <-
          ZIO.foreach(questionRows.sortBy(_.displayOrder)): q =>
            val qid = q.id.asString
            for
              questionText <- texts(qid)
              answers      <- answerCount(qid)
              options      <-
                ZIO.foreach(optionRows.filter(_.questionId == q.id).sortBy(_.displayOrder)): o =>
                  texts(o.id.asString).map: t =>
                    OptionView(o.id.asString, o.displayOrder, o.isActive == 1, t)
            yield QuestionView(
              id = qid,
              displayOrder = q.displayOrder,
              required = q.isRequired == 1,
              points = q.pointsAwarded,
              active = q.isActive == 1,
              answers = answers,
              text = questionText,
              options = options,
              // Raw config rather than a decoded rule: `decodeRules` silently drops anything that
              // does not match the question type's ADT, so showing what is stored is the only way
              // to see a rule that exists in the table but never applies at runtime.
              rules = ruleRows
                .filter(r => r.questionId == q.id && r.isActive == 1)
                .map(_.ruleConfig)
            )
            end for
      yield SurveyView(
        id = survey.id.asString,
        category = survey.category,
        name = survey.name,
        active = survey.isActive == 1,
        questions = views)
      end for

  val get: McpToolHandlerR[QuillSqlite] = McpTool("get_survey")
    .description(
      "The full contents of a survey: every question in display order with its texts, options, validation rules and how many answers reference it. Retired questions and options are included, marked inactive.")
    .readOnly
    .handle((in: GetRequest) => viewOf(in.surveyId))

  /** Which half of the server a survey belongs to.
    *
    * The three identification categories are deployment-wide: `SurveyRepositoryQuill` picks them by
    * category with no location filter, so there is one of each for every portal at once. Everything
    * else hangs off a video and therefore off a location.
    *
    * That difference is the whole reason the question tools come in pairs. An operator who looks
    * after one cafe may write their own advertiser questions all day; the question every guest of
    * every location is asked before being identified is not theirs to touch.
    */
  private def isIdentification(category: String): Boolean = IdentificationCategory
    .storedValues
    .contains(category)

  /** Refuses a question that belongs to the other half, naming the tool that does take it.
    *
    * `set_question_active` and `reorder_questions` are addressed by question and survey id, and
    * neither says which kind it is — so a caller that guesses wrong gets a sentence telling it
    * where to go, rather than a scope error that reads like a permissions problem.
    */
  private def requireCategory(
      category: String,
      wantIdentification: Boolean,
      otherTool: String): ZIO[Any, String, Unit] =
    ZIO
      .when(isIdentification(category) != wantIdentification)(
        ZIO.fail(
          if wantIdentification then
            s"That question belongs to a '$category' survey, which is scoped to a location. Use $otherTool."
          else
            s"That question belongs to the deployment-wide '$category' survey, asked of every guest of every location. Use $otherTool, which requires identity:write."))
      .unit

  /** Correcting a question that is already being asked.
    *
    * Paired by scope like the rest, for the reason `requireCategory` records: the three
    * identification surveys are asked of every guest of every location, so rewording one is not a
    * per-location change and must not be reachable with a location's token.
    *
    * Until this existed the only way to fix a typo was to retire the question and create a new one.
    * That is not equivalent: `answers.question_id` keeps pointing at the retired question, so the
    * history splits in two and nothing says the halves are the same question. Rewording in place
    * keeps them together — at the cost that answers recorded before the edit were given to the old
    * wording, which the response says out loud rather than leaving to be inferred.
    */
  private def update(wantIdentification: Boolean, otherTool: String): McpToolHandlerR[QuillSqlite] =
    McpTool(
      if wantIdentification then
        "update_identification_question"
      else
        "update_question")
      .description(
        if wantIdentification then
          "Correct a question in one of the three deployment-wide identification surveys: its wording, help text, placeholder, whether it is required, or its points. Every guest of every location is asked these. Omitted fields are left alone. Cannot change the question's type or its options — retire it and create a new one for that."
        else
          "Correct a question in a video's survey: its wording, help text, placeholder, whether it is required, or its points. Omitted fields are left alone. Cannot change the question's type or its options — retire it and create a new one for that. Answers already given are kept and still point at this question, so they were answers to the old wording.")
      .requireScopes(
        if wantIdentification then
          Auth.IdentityWrite
        else
          Auth.ContentWrite)
      .handle: (in: UpdateQuestionRequest) =>
        ZIO.serviceWithZIO[QuillSqlite]: quill =>
          import quill.*
          for
            question <- findQuestion(in.questionId)
            surveys  <- run(query[SurveyRow]).orDie
            category = surveys
              .find(_.id.asString == question.surveyId.asString)
              .map(_.category)
              .getOrElse("")
            _   <- requireCategory(category, wantIdentification, otherTool)
            _   <- validatePoints(in.points)
            now <- Clock.instant.map(_.toString)
            // The texts first and the row second, so a rejected `points` cannot leave the wording
            // rewritten against the old value. Both are idempotent, so a failure part way is
            // recoverable by calling again — there is no transaction to lean on here.
            _ <- ZIO.foreachDiscard(in.text)(replaceTexts(in.questionId, _, now))
            _ <- ZIO.foreachDiscard(in.description)(replaceTexts(s"${in.questionId}_desc", _, now))
            _ <-
              ZIO.foreachDiscard(in.placeholder)(
                replaceTexts(s"${in.questionId}_placeholder", _, now))
            rowFields =
              List(
                in.required
                  .map(r =>
                    "required" -> (
                      if r then
                        1
                      else
                        0
                    )),
                in.points.map("points" -> _)).flatten
            _ <-
              ZIO.when(rowFields.nonEmpty):
                val required =
                  in.required
                    .fold(question.isRequired)(r =>
                      if r then
                        1
                      else
                        0)
                val points = in.points.getOrElse(question.pointsAwarded)
                run(
                  questionByIdQuery(lift(in.questionId)).update(
                    _.isRequired    -> lift(required),
                    _.pointsAwarded -> lift(points)))
                  .orDie
                  .flatMap(Writes.expectOne(_, s"Updating question '${in.questionId}'"))
            count <- answerCount(in.questionId)
            written =
              List(
                in.text.map(_ => "text"),
                in.description.map(_ => "description"),
                in.placeholder.map(_ => "placeholder"),
                in.required.map(_ => "required"),
                in.points.map(_ => "points")
              ).flatten
          yield UpdateQuestionResponse(in.questionId, written, count)
          end for

  val updateQuestion: McpToolHandlerR[QuillSqlite] = update(
    wantIdentification = false,
    otherTool = "update_identification_question")

  val updateIdentificationQuestion: McpToolHandlerR[QuillSqlite] = update(
    wantIdentification = true,
    otherTool = "update_question")

  private def setActive(
      wantIdentification: Boolean,
      otherTool: String): McpToolHandlerR[QuillSqlite] = McpTool(
    if wantIdentification then
      "set_identification_question_active"
    else
      "set_question_active")
    .description(
      if wantIdentification then
        "Retire a question from one of the three deployment-wide identification surveys, or bring it back. Every guest of every location is asked these, so this is not a per-location change. Never deletes: answers reference questions, so a retired one keeps resolving for everything already recorded."
      else
        "Retire a question from an advertiser's survey, or bring it back. Never deletes: answers reference questions, so a retired question keeps resolving for everything already recorded. Reports how many answers that is.")
    .requireScopes(
      if wantIdentification then
        Auth.IdentityWrite
      else
        Auth.ContentWrite)
    .handle: (in: SetQuestionActiveRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          question <- findQuestion(in.questionId)
          surveys  <- run(query[SurveyRow]).orDie
          category = surveys
            .find(_.id.asString == question.surveyId.asString)
            .map(_.category)
            .getOrElse("")
          _ <- requireCategory(category, wantIdentification, otherTool)
          flag =
            if in.active then
              1
            else
              0
          affected <-
            run(questionByIdQuery(lift(in.questionId)).update(_.isActive -> lift(flag))).orDie
          _ <- Writes.expectOne(
            affected,
            s"Setting question '${in.questionId}' active=${in.active}")
          answers  <- answerCount(in.questionId)
          siblings <- run(questionsOfQuery(lift(question.surveyId.asString))).orDie
          // Retiring the last active question leaves a survey that can never be served; worth
          // seeing in the response rather than discovering through an empty flow.
          remaining =
            siblings.count(q => q.isActive == 1 || q.id.asString == in.questionId) - (
              if in.active then
                0
              else
                1
            )
        yield SetQuestionActiveResponse(in.questionId, in.active, answers, remaining)
        end for

  val setQuestionActive: McpToolHandlerR[QuillSqlite] = setActive(
    wantIdentification = false,
    otherTool = "set_identification_question_active")

  val setIdentificationQuestionActive: McpToolHandlerR[QuillSqlite] = setActive(
    wantIdentification = true,
    otherTool = "set_question_active")

  private def reorderQuestions(
      wantIdentification: Boolean,
      otherTool: String): McpToolHandlerR[QuillSqlite] = McpTool(
    if wantIdentification then
      "reorder_identification_questions"
    else
      "reorder_questions")
    .description(
      if wantIdentification then
        "Set the order the questions of a deployment-wide identification survey are asked in. Give every active question of the survey exactly once; display_order is rewritten to match. This is the order every guest of every location sees."
      else
        "Set the order an advertiser survey's questions are asked in. Give every active question of the survey exactly once; display_order is rewritten to match the list.")
    .requireScopes(
      if wantIdentification then
        Auth.IdentityWrite
      else
        Auth.ContentWrite)
    .handle: (in: ReorderRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          surveys <- run(query[SurveyRow]).orDie
          survey_ <- ZIO
            .succeed(surveys.find(_.id.asString == in.surveyId))
            .someOrFail(
              s"No survey with id '${in.surveyId}'. Call list_surveys to see what exists.")
          _    <- requireCategory(survey_.category, wantIdentification, otherTool)
          rows <- run(questionsOfQuery(lift(in.surveyId))).orDie
          _    <-
            ZIO.when(rows.isEmpty)(
              ZIO.fail(s"No survey with id '${in.surveyId}', or it has no questions."))
          active  = rows.filter(_.isActive == 1).map(_.id.asString).toSet
          given_  = in.orderedQuestionIds.toSet
          missing = (active -- given_).toList.sorted
          extra   = (given_ -- active).toList.sorted
          // Demanding the complete set is what keeps display_order total and unambiguous; a partial
          // list would leave some questions sharing an order with no defined tie-break.
          _ <-
            ZIO.when(missing.nonEmpty || extra.nonEmpty)(
              ZIO.fail(
                s"The list must name every active question exactly once. Missing: ${missing
                    .mkString(", ")}. Not in this survey or not active: ${extra.mkString(", ")}."))
          _ <-
            ZIO.when(in.orderedQuestionIds.distinct.size != in.orderedQuestionIds.size)(
              ZIO.fail("The list repeats a question."))
          _ <-
            ZIO.foreachDiscard(in.orderedQuestionIds.zipWithIndex): (id, index) =>
              run(questionByIdQuery(lift(id)).update(_.displayOrder -> lift(index + 1)))
                .orDie
                .flatMap(Writes.expectOne(_, s"Reordering question '$id'"))
        yield ReorderResponse(in.surveyId, in.orderedQuestionIds)
        end for

  val reorder: McpToolHandlerR[QuillSqlite] = reorderQuestions(
    wantIdentification = false,
    otherTool = "reorder_identification_questions")

  val reorderIdentification: McpToolHandlerR[QuillSqlite] = reorderQuestions(
    wantIdentification = true,
    otherTool = "reorder_questions")

  // ───────────────────────────────────────────────────────────────────────────
  // Creation
  //
  // The input types exist to keep the operator out of decisions the agent can make on its own.
  // Anything with a sensible default is an Option — zio-schema marks only non-Option fields as
  // required, and a Scala default does not reach the schema — and every constrained field is an
  // enum, so the legal values are in the schema rather than in prose the caller may not read.
  // ───────────────────────────────────────────────────────────────────────────

  final case class CreateSurveyRequest(
      @description(
        "The video this survey follows. Required: it is what findNextAdvertiserSurvey matches on, and the survey inherits that video's location.")
      videoId: String,
      @description("Label for operators. Not shown to guests; safe to omit.")
      name: Option[String])

  /** Its own request type rather than a category on the one above, so the schema refuses
    * `advertiser` outright instead of the handler explaining afterwards that it needed a video.
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

  given Schema[LocalizedText] = DeriveSchema.gen
  given Schema[AnswerOption] = DeriveSchema.gen
  given Schema[CreateSurveyRequest] = DeriveSchema.gen
  given Schema[CreateIdentificationSurveyRequest] = DeriveSchema.gen
  given Schema[CreateSurveyResponse] = DeriveSchema.gen
  given Schema[CreateQuestionRequest] = DeriveSchema.gen
  given Schema[CreateQuestionResponse] = DeriveSchema.gen

  /** Flattens the discriminated spec into what the row needs.
    *
    * The kind-versus-type pairing that used to be checked here is gone: `AnswerFormat` makes it
    * unrepresentable, so an `input` question cannot carry a selection rule to be rejected. What is
    * left is parsing each value, which the types cannot do.
    */
  private def flatten(
      spec: AnswerFormat): (String, List[AnswerOption], List[(String, Option[String])]) =
    def text(rules: Option[List[TextRule]]) = rules
      .getOrElse(Nil)
      .map(r => (r.kind.toString, r.value))
    def selection(rules: Option[List[SelectionRule]]) = rules
      .getOrElse(Nil)
      .map(r => (r.kind.toString, Some(r.value)))
    def range(rules: Option[List[RangeRule]]) = rules
      .getOrElse(Nil)
      .map(r => (r.kind.toString, Some(r.value)))
    spec match
      case AnswerFormat.Radio(options) =>
        ("radio", options, Nil)
      case AnswerFormat.Dropdown(options) =>
        ("dropdown", options, Nil)
      case AnswerFormat.Checkbox(options, rules) =>
        ("checkbox", options, selection(rules))
      case AnswerFormat.Input(rules) =>
        ("input", Nil, text(rules))
      case AnswerFormat.Numeric(rules) =>
        ("numeric", Nil, range(rules))
      case AnswerFormat.Date(rules) =>
        ("date", Nil, range(rules))
  end flatten

  /** Builds the stored rule JSON. Only the value can be wrong now; the kind is already known to
    * belong to the question type.
    *
    * The config is constructed here rather than accepted from the caller so its shape is always the
    * one `SurveyRepositoryQuill.decodeRules` expects — it decodes with `.toOption` and flat-maps,
    * so a rule it cannot read disappears at runtime with no error.
    */
  private def ruleConfig(
      questionType: String,
      kind: String,
      value: Option[String]): Either[String, String] =
    def wholeNumber(v: String) = v
      .toIntOption
      .toRight(s"The $kind rule needs a whole number, got '$v'.")
      .map(_.toString)
    def number(v: String) = v
      .toDoubleOption
      .toRight(s"The $kind rule needs a number, got '$v'.")
      .map(_ => v)
    def isoDate(v: String) = scala
      .util
      .Try(java.time.LocalDate.parse(v))
      .toEither
      .left
      .map(_ => s"The $kind rule needs an ISO date (yyyy-mm-dd), got '$v'.")
      .map(_ => s""""$v"""")
    def quoted(v: String) = Right(s""""${v.replace("\\", "\\\\").replace("\"", "\\\"")}"""")
    def needs(render: String => Either[String, String]) = value
      .toRight(s"The $kind rule needs a value.")
      .flatMap(render)

    val rendered: Either[String, Option[String]] =
      (questionType, kind) match
        case (_, "email") | (_, "url") =>
          Right(None)
        case (_, "pattern") =>
          needs(quoted).map(Some(_))
        case ("date", _) =>
          needs(isoDate).map(Some(_))
        case ("numeric", _) =>
          needs(number).map(Some(_))
        case _ =>
          needs(wholeNumber).map(Some(_))

    rendered.map:
      case Some(v) =>
        s"""{"type":"$kind","value":$v}"""
      case None =>
        s"""{"type":"$kind"}"""
  end ruleConfig

  private def validateTexts(texts: List[LocalizedText], what: String): ZIO[Any, String, Unit] =
    for
      _ <-
        ZIO.when(texts.exists(t => t.locale.isBlank || t.value.isBlank))(
          ZIO.fail(s"$what has an entry with a blank locale or value."))
      duplicates = texts.groupBy(_.locale).filter(_._2.size > 1).keys.toList.sorted
      _ <-
        ZIO.when(duplicates.nonEmpty)(
          ZIO.fail(s"$what repeats the locale(s) ${duplicates.mkString(", ")}."))
    yield ()

  private[mcp] def writeTexts(
      entityId: String,
      texts: List[LocalizedText],
      now: String): ZIO[QuillSqlite, String, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    ZIO.foreachDiscard(texts): t =>
      val row = LocalizedTextRow(
        id = UUID.randomUUID.toString,
        entityId = entityId,
        locale = t.locale,
        value = t.value,
        category = "backend",
        createdAt = now,
        updatedAt = now,
        locationId = None,
        isActive = 1
      )
      run(query[LocalizedTextRow].insertValue(lift(row)))
        .orDie
        .flatMap(Writes.expectOne(_, s"Text for '$entityId' (${t.locale})"))

  private inline def backendTextQuery = quote: (entityIdParam: String, localeParam: String) =>
    query[LocalizedTextRow].filter(t =>
      t.entityId == entityIdParam && t.locale == localeParam && t.category == "backend")

  /** The editing counterpart of [[writeTexts]], upserting on `(entity_id, locale)`.
    *
    * `writeTexts` inserts blind, which is right exactly once — when the entity is being created and
    * cannot have texts yet. Every later write has to find the existing row: V12's unique index
    * covers `(entity_id, locale, location_id)`, so a second blind insert for the same locale is
    * rejected, and rqlite reports that rejection inside an HTTP 200 as zero rows. An editing tool
    * built on `writeTexts` would therefore report success and change nothing.
    *
    * Only the locales given are touched. Omitting one leaves it alone rather than deleting it, so
    * correcting the Spanish wording of a question cannot silently drop its English.
    */
  private[mcp] def replaceTexts(
      entityId: String,
      texts: List[LocalizedText],
      now: String): ZIO[QuillSqlite, String, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    ZIO.foreachDiscard(texts): t =>
      for
        existing <- run(backendTextQuery(lift(entityId), lift(t.locale))).orDie.map(_.headOption)
        affected <-
          existing match
            case Some(row) =>
              run(
                query[LocalizedTextRow]
                  .filter(_.id == lift(row.id))
                  // isActive -> 1 for the reason `I18nTools.upsert` gives: rewriting a text undoes
                  // an earlier retirement rather than leaving a row nobody can see.
                  .update(_.value -> lift(t.value), _.updatedAt -> lift(now), _.isActive -> 1))
                .orDie
            case None =>
              run(
                query[LocalizedTextRow].insertValue(
                  lift(
                    LocalizedTextRow(
                      id = UUID.randomUUID.toString,
                      entityId = entityId,
                      locale = t.locale,
                      value = t.value,
                      category = "backend",
                      createdAt = now,
                      updatedAt = now,
                      locationId = None,
                      isActive = 1
                    )))).orDie
        _ <- Writes.expectOne(affected, s"Text for '$entityId' (${t.locale})")
      yield ()

  /** The one place a survey row is written by these tools.
    *
    * Both tools land here so the uniqueness guard cannot be bypassed by whichever of them grows a
    * second code path later. `create_advertiser_video` writes its own row, which is deliberate: it
    * builds the video and its survey in one call and the survey is unambiguous by construction — it
    * belongs to the video it just created.
    */
  private def insertSurvey(
      category: String,
      videoId: Option[String],
      locationId: Option[String],
      /** Copied from the video, like `locationId`, so answers to an advertiser's questions are
        * attributed to that advertiser in the event log. It was being written as `None` for every
        * survey, which is correct for a promo and loses the attribution for an ad.
        */
      advertiserId: Option[String],
      name: Option[String],
      global: Boolean)
      : ZIO[QuillSqlite, String, CreateSurveyResponse] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    for
      existing <- run(query[SurveyRow]).orDie
      // Identification surveys are chosen by category alone, with no location filter anywhere in
      // SurveyRepositoryQuill, so a second active one of the same category makes which gets
      // served depend on display_order across unrelated surveys.
      clashes = existing.filter(s => global && s.isActive == 1 && s.category == category)
      _ <-
        ZIO.when(clashes.nonEmpty)(
          ZIO.fail(
            s"An active '$category' survey already exists (id ${clashes
                .head
                .id
                .asString}). Identification surveys are global and picked by category, so two would be ambiguous."))
      now = java.time.Instant.now.toString
      id  = survey.Id.generate
      affected <-
        run(
          query[SurveyRow].insertValue(
            lift(SurveyRow(id, category, advertiserId, videoId, locationId, 1, now, name)))).orDie
      _ <- Writes.expectOne(affected, s"Creating $category survey")
    yield CreateSurveyResponse(id.asString, category)
    end for

  /** Identification surveys live in [[createIdentificationSurvey]], behind a different scope. This
    * one is a location's, through the video it follows — whether that video is an advertiser's or a
    * promo. The selection only requires an *advertiser's* video to still have a question left; a
    * promo plays regardless, and its survey, if it has one, is asked afterwards the same way.
    */
  val createSurvey: McpToolHandlerR[QuillSqlite] = McpTool("create_survey")
    .description(
      "Create the survey shown after a video — an advertiser's or one of the venue's own promos. It inherits the video's location and advertiser, so there is nothing to say about where it belongs or whose it is. Call read_skill('add-survey') first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateSurveyRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          // Verified rather than trusted: a survey pointing at a video that does not exist is
          // reachable by nothing, and the foreign key will not say so.
          video <- run(videoByIdQuery(lift(in.videoId)))
            .orDie
            .map(_.headOption)
            .someOrFail(s"No video with id '${in.videoId}'.")
          // Both taken from the video rather than asked for. A video survey is scoped to a location
          // through its video — the selection filters videos by location and the survey follows —
          // so a location_id of its own could only ever disagree with it, and the same holds for
          // the advertiser. A promo's video carries neither; the survey then carries neither, and
          // is served and answered all the same.
          response <- insertSurvey(
            category = SurveyCategory.advertiser.stored,
            videoId = Some(in.videoId),
            locationId = video.locationId,
            advertiserId = video.advertiserId,
            name = in.name,
            global = false
          )
        yield response
        end for

  /** Behind [[Auth.IdentityWrite]] rather than `content:write`, which is the whole point of it
    * being a separate tool.
    *
    * These three are asked of every guest of every location — `SurveyRepositoryQuill` picks them by
    * category with no location filter — so replacing one changes the identification flow for the
    * entire deployment. An operator trusted with a cafe's videos should not be able to do that by
    * holding the same token, and a scope declared on the tool is what lets `tools/list` say so.
    */
  val createIdentificationSurvey: McpToolHandlerR[QuillSqlite] = McpTool(
    "create_identification_survey")
    .description(
      "Create one of the three deployment-wide surveys asked before a guest is identified: email, profiling or location. Not scoped to any location — there is one of each for every portal at once, and creating a second of the same kind is refused. Call read_skill('add-survey') first.")
    .requireScopes(Auth.IdentityWrite)
    .handle: (in: CreateIdentificationSurveyRequest) =>
      insertSurvey(
        category = in.category.stored,
        videoId = None,
        locationId = None,
        advertiserId = None,
        name = in.name,
        global = true)

  /** Writes one question with its texts, options and rules.
    *
    * Extracted so `create_advertiser_video` can build a video's survey in the same call that
    * registers the video, rather than leaving the operator to add questions afterwards — which is
    * the state `captal video add` left behind, with "TODO" standing in for the wording.
    *
    * Takes `displayOrder` rather than computing it: a caller writing several questions in one go
    * knows the order it wants and should not have to re-read the table between each.
    */
  private[mcp] def writeQuestion(
      surveyId: survey.Id,
      surveyCategory: String,
      text: List[LocalizedText],
      spec: AnswerFormat,
      descriptions: List[LocalizedText],
      placeholders: List[LocalizedText],
      required: Boolean,
      points: Int,
      hierarchyLevel: Option[Hierarchy],
      displayOrder: Int,
      now: String)
      : ZIO[QuillSqlite, String, CreateQuestionResponse] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    val (questionType, options, ruleSpecs) = flatten(spec)
    for
      _ <-
        ZIO.when(text.isEmpty)(
          ZIO.fail("A question needs text in at least one locale, or it cannot be rendered."))
      _ <- validateTexts(text, "text")
      _ <- validateTexts(descriptions, "description")
      _ <- validateTexts(placeholders, "placeholder")
      // Whether a type takes options is structural; that it takes at least one is not.
      _ <-
        ZIO.when(
          options.isEmpty &&
            (questionType == "radio" || questionType == "checkbox" || questionType == "dropdown"))(
          ZIO.fail(s"A $questionType question needs at least one option."))
      _ <-
        ZIO.foreachDiscard(options.zipWithIndex): (o, i) =>
          ZIO.when(o.text.isEmpty)(ZIO.fail(s"Option ${i + 1} has no text.")) *>
            validateTexts(o.text, s"option ${i + 1}")
      ruleConfigs <-
        ZIO.foreach(ruleSpecs): (kind, value) =>
          ZIO.fromEither(ruleConfig(questionType, kind, value))
      _ <-
        ZIO.when(hierarchyLevel.isDefined && surveyCategory != "location")(
          ZIO.fail("hierarchyLevel only applies to questions in a location survey."))
      _ <- ZIO.when(points < 0)(ZIO.fail(s"Points must not be negative, got $points."))
      questionId = survey.question.Id.generate
      affected <-
        run(
          query[QuestionRow].insertValue(
            lift(
              QuestionRow(
                questionId,
                surveyId,
                questionType,
                points,
                displayOrder,
                hierarchyLevel.map(_.toString),
                if required then
                  1
                else
                  0
                ,
                now,
                1)))).orDie
      _         <- Writes.expectOne(affected, s"Creating question in survey '${surveyId.asString}'")
      _         <- writeTexts(questionId.asString, text, now)
      _         <- writeTexts(s"${questionId.asString}_desc", descriptions, now)
      _         <- writeTexts(s"${questionId.asString}_placeholder", placeholders, now)
      optionIds <-
        ZIO.foreach(options.zipWithIndex): (o, i) =>
          val optionId = survey.question.OptionId.generate
          for
            written <-
              run(
                query[QuestionOptionRow].insertValue(
                  lift(QuestionOptionRow(optionId, questionId, i + 1, None, 1)))).orDie
            _ <- Writes.expectOne(written, s"Creating option ${i + 1}")
            _ <- writeTexts(optionId.asString, o.text, now)
          yield optionId.asString
      _ <-
        ZIO.foreachDiscard(ruleConfigs): config =>
          run(
            query[QuestionRuleRow].insertValue(
              lift(QuestionRuleRow(UUID.randomUUID.toString, questionId, questionType, config, 1))))
            .orDie
            .flatMap(Writes.expectOne(_, s"Creating rule $config"))
    yield CreateQuestionResponse(questionId.asString, displayOrder, optionIds)
    end for

  private def addQuestion(
      wantIdentification: Boolean,
      otherTool: String): McpToolHandlerR[QuillSqlite] = McpTool(
    if wantIdentification then
      "create_identification_question"
    else
      "create_question")
    .description(
      if wantIdentification then
        "Add a question to one of the three deployment-wide identification surveys. Every guest of every location answers it before being identified, so this is not a per-location change. Appended last; use reorder_identification_questions to move it. Call read_skill('add-survey') for the locale constraints, which the schema cannot express."
      else
        "Add a question to an advertiser's survey. Appended last; use reorder_questions to move it. Only the wording has to come from the operator — the question shape carries what varies by type, and everything else has a default. Call read_skill('add-survey') for the locale constraints, which the schema cannot express.")
    .requireScopes(
      if wantIdentification then
        Auth.IdentityWrite
      else
        Auth.ContentWrite)
    .handle: (in: CreateQuestionRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          surveys <- run(query[SurveyRow]).orDie
          survey_ <- ZIO
            .succeed(surveys.find(_.id.asString == in.surveyId))
            .someOrFail(
              s"No survey with id '${in.surveyId}'. Call list_surveys to see what exists.")
          _        <- requireCategory(survey_.category, wantIdentification, otherTool)
          siblings <- run(questionsOfQuery(lift(in.surveyId))).orDie
          written  <- writeQuestion(
            surveyId = survey_.id,
            surveyCategory = survey_.category,
            text = in.text,
            spec = in.answer,
            descriptions = in.description.getOrElse(Nil),
            placeholders = in.placeholder.getOrElse(Nil),
            required = in.required.getOrElse(true),
            points = in.points.getOrElse(10),
            hierarchyLevel = in.hierarchyLevel,
            displayOrder = siblings.map(_.displayOrder).maxOption.getOrElse(0) + 1,
            now = java.time.Instant.now.toString
          )
        yield written
        end for

  val createQuestion: McpToolHandlerR[QuillSqlite] = addQuestion(
    wantIdentification = false,
    otherTool = "create_identification_question")

  val createIdentificationQuestion: McpToolHandlerR[QuillSqlite] = addQuestion(
    wantIdentification = true,
    otherTool = "create_question")

  /** Six tools where there were three, in pairs that differ only in which surveys they accept and
    * what they require to use them.
    *
    * The alternative was one tool each, declaring `content:write` and quietly demanding
    * `identity:write` once it saw the survey's category. That reads better at the call site and
    * makes `tools/list` lie: a tool would publish one requirement and enforce another, which is the
    * failure this codebase has spent the day finding in other forms.
    */
  val all: List[McpToolHandlerR[QuillSqlite]] = List(
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
