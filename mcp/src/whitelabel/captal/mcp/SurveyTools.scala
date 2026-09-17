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
  QuestionOptionRow,
  QuestionRow,
  QuestionRuleRow,
  SurveyRow
}
import zio.*
import zio.schema.annotation.{description, discriminatorName, rejectExtraFields}
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given

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

final case class TextIn(
    @description("BCP 47 tag — 'es', 'en'. One entry per locale the location serves.")
    locale: String,
    @description("The text guests see in that locale.")
    value: String)

final case class OptionIn(
    @description("The option's label, one entry per locale.")
    text: List[TextIn])

final case class TextRuleIn(
    @description("Which rule. Only the kinds an input question accepts are listed here.")
    kind: TextRuleKind,
    @description(
      "Omit for email and url. A whole number for the length rules; a regex for pattern.")
    value: Option[String])
final case class SelectionRuleIn(
    @description("Which bound to set on how many options may be picked.")
    kind: SelectionRuleKind,
    @description("A whole number.")
    value: String)
final case class RangeRuleIn(
    @description("Which end of the range to set.")
    kind: RangeRuleKind,
    @description(
      "A whole number for rating, any number for numeric, an ISO date (yyyy-mm-dd) for date.")
    value: String)

/** What varies by question type, and only that.
  *
  * Discriminated so the schema carries the constraint instead of the prose: an `input` variant has
  * no `options` field to pass, and its rules are text rules by type. The fields every question
  * shares stay flat in [[SurveyTools.CreateQuestionIn]] rather than being repeated across seven
  * variants.
  *
  * The discriminator is put back into the generated schema by `ToolSchema.withDiscriminator` —
  * JsonSchemaGen drops it, which would leave the schema describing a payload the decoder rejects.
  */
@discriminatorName("questionType")
enum QuestionSpec:
  @rejectExtraFields
  case Radio(
      @description("The choices, in the order they are shown. At least one.")
      options: List[OptionIn])
  @rejectExtraFields
  case Dropdown(
      @description("The choices, in the order they are shown. At least one.")
      options: List[OptionIn])
  @rejectExtraFields
  case Checkbox(
      @description("The choices, in the order they are shown. At least one.")
      options: List[OptionIn],
      @description(
        "How many may be picked: min_selections, max_selections. Omit for no limit either way.")
      rules: Option[List[SelectionRuleIn]])
  @rejectExtraFields
  case Input(
      @description(
        "What the text must satisfy: min_length, max_length, pattern, email, url. Omit for none.")
      rules: Option[List[TextRuleIn]])
  @rejectExtraFields
  case Rating(
      @description("The bounds of the scale: min, max, as whole numbers. Omit for the default.")
      rules: Option[List[RangeRuleIn]])
  @rejectExtraFields
  case Numeric(
      @description("The accepted range: min, max. Any number. Omit for unbounded.")
      rules: Option[List[RangeRuleIn]])
  @rejectExtraFields
  case Date(
      @description("The accepted range: min, max, as ISO dates (yyyy-mm-dd). Omit for unbounded.")
      rules: Option[List[RangeRuleIn]])
end QuestionSpec

object QuestionSpec:
  given schema: Schema[QuestionSpec] = DeriveSchema.gen

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
          s"QuestionSpec no longer derives to an enum schema but to $other; the discriminator patch has nothing to attach to.")

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
  final case class LocalizedValue(
      @description("BCP 47 tag.")
      locale: String,
      @description("The stored text for that locale.")
      value: String)
  final case class OptionView(
      @description("Stable across edits; answers reference it.")
      id: String,
      @description("Position within the question, ascending.")
      displayOrder: Int,
      @description("A retired option stops being offered but keeps the answers that chose it.")
      active: Boolean,
      @description("The label, per locale. A locale missing here shows nothing for this option.")
      text: List[LocalizedValue])
  final case class QuestionView(
      @description("What set_question_active and reorder_questions take.")
      id: String,
      @description(
        "As stored, lowercase — 'radio', 'input'. The create tools take the capitalised form of the same set.")
      questionType: String,
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
      text: List[LocalizedValue],
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

  final case class ListIn(
      @description("Retired surveys are hidden by default; set this to see them too.")
      includeInactive: Boolean = false)
  final case class SurveySummary(
      @description("Pass to get_survey for the questions themselves.")
      id: String,
      @description("What the survey is for.")
      category: String,
      @description("Operator-facing label. Absent when none was set.")
      name: Option[String],
      @description("A retired survey is served to nobody.")
      active: Boolean,
      @description(
        "Questions currently in rotation. Zero with active true is a survey that would be served and has nothing to ask.")
      activeQuestions: Int)
  final case class ListOut(
      @description("Call get_survey with an id for the questions.")
      surveys: List[SurveySummary])

  final case class GetIn(
      @description("Which survey to read. Call list_surveys for the ids.")
      surveyId: String)

  final case class SetQuestionActiveIn(
      @description("Which question to retire or bring back.")
      questionId: String,
      @description(
        "False retires it. This is the only removal there is: answers reference the question, so deleting would strand them.")
      active: Boolean)
  final case class SetQuestionActiveOut(
      @description("The question changed.")
      questionId: String,
      @description("Its state after the change.")
      active: Boolean,
      @description("Answers still referencing it. Retiring loses none of them.")
      answersPreserved: Int,
      @description(
        "Questions left in rotation in this survey. Zero means the survey would now be served with nothing to ask.")
      remainingActive: Int)

  final case class ReorderIn(
      @description("Which survey to reorder.")
      surveyId: String,
      @description(
        "Every question in the survey, retired ones included, in the order they should be served. A partial list is refused rather than applied to the ids it names.")
      orderedQuestionIds: List[String])
  final case class ReorderOut(
      @description("The survey reordered.")
      surveyId: String,
      @description("The order as stored, which should match what was sent.")
      order: List[String])

  given Schema[LocalizedValue] = DeriveSchema.gen
  given Schema[OptionView] = DeriveSchema.gen
  given Schema[QuestionView] = DeriveSchema.gen
  given Schema[SurveyView] = DeriveSchema.gen
  given Schema[ListIn] = DeriveSchema.gen
  given Schema[SurveySummary] = DeriveSchema.gen
  given Schema[ListOut] = DeriveSchema.gen
  given Schema[GetIn] = DeriveSchema.gen
  given Schema[SetQuestionActiveIn] = DeriveSchema.gen
  given Schema[SetQuestionActiveOut] = DeriveSchema.gen
  given Schema[ReorderIn] = DeriveSchema.gen
  given Schema[ReorderOut] = DeriveSchema.gen

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

  private def texts(entityId: String): ZIO[QuillSqlite, Nothing, List[LocalizedValue]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(textsForQuery(lift(entityId)))
        .orDie
        .map(_.sortBy(_.locale).map(r => LocalizedValue(r.locale, r.value)))

  private def answerCount(questionId: String): ZIO[QuillSqlite, Nothing, Int] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(answerCountQuery(lift(questionId))).orDie.map(_.toInt)

  private def findQuestion(id: String): ZIO[QuillSqlite, String, QuestionRow] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(questionByIdQuery(lift(id)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No question with id '$id'. Call get_survey to see what a survey contains.")

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_surveys")
    .description(
      "Surveys with their category and how many active questions each has. Categories are email, profiling, location and advertiser.")
    .handle: (in: ListIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          surveys   <- run(query[SurveyRow]).orDie
          questions <- run(query[QuestionRow]).orDie
          visible   = surveys.filter(s => in.includeInactive || s.isActive == 1)
          summaries = visible
            .sortBy(s => (s.category, s.name.getOrElse("")))
            .map: s =>
              SurveySummary(
                id = s.id.asString,
                category = s.category,
                name = s.name,
                active = s.isActive == 1,
                activeQuestions = questions.count(q => q.surveyId == s.id && q.isActive == 1))
        yield ListOut(summaries)

  val get: McpToolHandlerR[QuillSqlite] = McpTool("get_survey")
    .description(
      "The full contents of a survey: every question in display order with its texts, options, validation rules and how many answers reference it. Retired questions and options are included, marked inactive.")
    .handle: (in: GetIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          surveys <- run(query[SurveyRow]).orDie
          survey  <- ZIO
            .succeed(surveys.find(_.id.asString == in.surveyId))
            .someOrFail(
              s"No survey with id '${in.surveyId}'. Call list_surveys to see what exists.")
          questionRows <- run(questionsOfQuery(lift(in.surveyId))).orDie
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
                questionType = q.questionType,
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

  val setQuestionActive: McpToolHandlerR[QuillSqlite] = McpTool("set_question_active")
    .description(
      "Retire a question or bring it back. Never deletes: answers reference questions, so a retired question keeps resolving for everything already recorded. Reports how many answers that is. See skill://add-survey/SKILL.md.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetQuestionActiveIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          question <- findQuestion(in.questionId)
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
        yield SetQuestionActiveOut(in.questionId, in.active, answers, remaining)
        end for

  val reorder: McpToolHandlerR[QuillSqlite] = McpTool("reorder_questions")
    .description(
      "Set the order questions are asked in. Give every active question of the survey exactly once; display_order is rewritten to match the list.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: ReorderIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
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
        yield ReorderOut(in.surveyId, in.orderedQuestionIds)
        end for

  // ───────────────────────────────────────────────────────────────────────────
  // Creation
  //
  // The input types exist to keep the operator out of decisions the agent can make on its own.
  // Anything with a sensible default is an Option — zio-schema marks only non-Option fields as
  // required, and a Scala default does not reach the schema — and every constrained field is an
  // enum, so the legal values are in the schema rather than in prose the caller may not read.
  // ───────────────────────────────────────────────────────────────────────────

  final case class CreateSurveyIn(
      @description(
        "What the survey is for. Identification surveys are asked before a guest has a location; the others belong to one.")
      category: SurveyCategory,
      @description("Label for operators. Not shown to guests; safe to omit.")
      name: Option[String],
      @description(
        "Required for advertiser surveys and rejected for the others: the video this survey follows. The survey inherits that video's location.")
      videoId: Option[String])
  final case class CreateSurveyOut(
      @description(
        "Pass to create_question to add questions. A survey with none is served to nobody.")
      surveyId: String,
      @description("The category as stored.")
      category: String)

  final case class CreateQuestionIn(
      @description("Which survey to append to. Call list_surveys for the ids.")
      surveyId: String,
      @description(
        "The wording, one entry per locale. Ask the operator for this; nothing else can supply it.")
      text: List[TextIn],
      @description(
        "The shape of the answer. questionType picks the variant and decides which other fields are allowed; a field the variant does not have is refused.")
      question: QuestionSpec,
      @description("Helper text under the question. Omit when there is none.")
      description: Option[List[TextIn]],
      @description("Placeholder inside the input. Only meaningful for input questions.")
      placeholder: Option[List[TextIn]],
      @description("Whether an answer is mandatory. Defaults to true.")
      required: Option[Boolean],
      @description("Points awarded for answering. Defaults to 10, which is what every survey uses.")
      points: Option[Int],
      @description("Only for questions in a location survey; rejected elsewhere.")
      hierarchyLevel: Option[Hierarchy])
  final case class CreateQuestionOut(
      @description("What set_question_active and reorder_questions take.")
      questionId: String,
      @description(
        "Where it landed: appended after the survey's existing questions. Use reorder_questions to move it.")
      displayOrder: Int,
      @description(
        "Ids of the options created, in the order given. Empty for the types that take none — and empty for a choice type means the options were dropped rather than stored.")
      optionIds: List[String])

  given Schema[TextIn] = DeriveSchema.gen
  given Schema[OptionIn] = DeriveSchema.gen
  given Schema[CreateSurveyIn] = DeriveSchema.gen
  given Schema[CreateSurveyOut] = DeriveSchema.gen
  given Schema[CreateQuestionIn] = DeriveSchema.gen
  given Schema[CreateQuestionOut] = DeriveSchema.gen

  /** Flattens the discriminated spec into what the row needs.
    *
    * The kind-versus-type pairing that used to be checked here is gone: `QuestionSpec` makes it
    * unrepresentable, so an `input` question cannot carry a selection rule to be rejected. What is
    * left is parsing each value, which the types cannot do.
    */
  private def flatten(
      spec: QuestionSpec): (String, List[OptionIn], List[(String, Option[String])]) =
    def text(rules: Option[List[TextRuleIn]]) = rules
      .getOrElse(Nil)
      .map(r => (r.kind.toString, r.value))
    def selection(rules: Option[List[SelectionRuleIn]]) = rules
      .getOrElse(Nil)
      .map(r => (r.kind.toString, Some(r.value)))
    def range(rules: Option[List[RangeRuleIn]]) = rules
      .getOrElse(Nil)
      .map(r => (r.kind.toString, Some(r.value)))
    spec match
      case QuestionSpec.Radio(options) =>
        ("radio", options, Nil)
      case QuestionSpec.Dropdown(options) =>
        ("dropdown", options, Nil)
      case QuestionSpec.Checkbox(options, rules) =>
        ("checkbox", options, selection(rules))
      case QuestionSpec.Input(rules) =>
        ("input", Nil, text(rules))
      case QuestionSpec.Rating(rules) =>
        ("rating", Nil, range(rules))
      case QuestionSpec.Numeric(rules) =>
        ("numeric", Nil, range(rules))
      case QuestionSpec.Date(rules) =>
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

  private def validateTexts(texts: List[TextIn], what: String): ZIO[Any, String, Unit] =
    for
      _ <-
        ZIO.when(texts.exists(t => t.locale.isBlank || t.value.isBlank))(
          ZIO.fail(s"$what has an entry with a blank locale or value."))
      duplicates = texts.groupBy(_.locale).filter(_._2.size > 1).keys.toList.sorted
      _ <-
        ZIO.when(duplicates.nonEmpty)(
          ZIO.fail(s"$what repeats the locale(s) ${duplicates.mkString(", ")}."))
    yield ()

  private def writeTexts(
      entityId: String,
      texts: List[TextIn],
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

  val createSurvey: McpToolHandlerR[QuillSqlite] = McpTool("create_survey")
    .description(
      "Create a survey. Advertiser surveys name the video they follow and inherit its location; identification surveys are global and are picked by category. Read skill://add-survey/SKILL.md first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateSurveyIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        val category = in.category.toString
        for
          // The video is what findNextAdvertiserSurvey matches on; without it the survey exists but
          // is unreachable.
          _ <-
            ZIO.when(in.category == SurveyCategory.advertiser && in.videoId.isEmpty)(
              ZIO.fail("An advertiser survey needs the videoId of the video it follows."))
          _ <-
            ZIO.when(in.category != SurveyCategory.advertiser && in.videoId.isDefined)(
              ZIO.fail(s"Only advertiser surveys attach to a video; this one is '$category'."))
          // Verified rather than trusted: a survey pointing at a video that does not exist is
          // reachable by nothing, and the foreign key will not say so.
          video <-
            ZIO.foreach(in.videoId): videoId =>
              run(videoByIdQuery(lift(videoId)))
                .orDie
                .map(_.headOption)
                .someOrFail(s"No video with id '$videoId'.")
          // Taken from the video rather than asked for. Advertiser surveys are scoped to a location
          // through their video — the selection filters videos by location and the survey follows —
          // so a location_id of its own could only ever disagree with it.
          locationId = video.flatMap(_.locationId)
          existing <- run(query[SurveyRow]).orDie
          // Identification surveys are chosen by category alone, with no location filter anywhere in
          // SurveyRepositoryQuill, so a second active one of the same category makes which gets
          // served depend on display_order across unrelated surveys.
          clashes = existing.filter(s =>
            s.isActive == 1 && s.category == category && in.category != SurveyCategory.advertiser)
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
                lift(SurveyRow(id, category, None, in.videoId, locationId, 1, now, in.name)))).orDie
          _ <- Writes.expectOne(affected, s"Creating $category survey")
        yield CreateSurveyOut(id.asString, category)
        end for

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
      text: List[TextIn],
      spec: QuestionSpec,
      descriptions: List[TextIn],
      placeholders: List[TextIn],
      required: Boolean,
      points: Int,
      hierarchyLevel: Option[Hierarchy],
      displayOrder: Int,
      now: String): ZIO[QuillSqlite, String, CreateQuestionOut] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
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
            options.isEmpty && (
              questionType == "radio" || questionType == "checkbox" || questionType == "dropdown"
            ))(ZIO.fail(s"A $questionType question needs at least one option."))
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
        _ <- Writes.expectOne(affected, s"Creating question in survey '${surveyId.asString}'")
        _ <- writeTexts(questionId.asString, text, now)
        _ <- writeTexts(s"${questionId.asString}_desc", descriptions, now)
        _ <- writeTexts(s"${questionId.asString}_placeholder", placeholders, now)
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
                lift(
                  QuestionRuleRow(UUID.randomUUID.toString, questionId, questionType, config, 1))))
              .orDie
              .flatMap(Writes.expectOne(_, s"Creating rule $config"))
      yield CreateQuestionOut(questionId.asString, displayOrder, optionIds)
      end for

  val createQuestion: McpToolHandlerR[QuillSqlite] = McpTool("create_question")
    .description(
      "Add a question to a survey. Appended last; use reorder_questions to move it. Only the wording has to come from the operator — the question shape carries what varies by type, and everything else has a default. Read skill://add-survey/SKILL.md for the locale constraints, which the schema cannot express.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateQuestionIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          surveys <- run(query[SurveyRow]).orDie
          survey_ <- ZIO
            .succeed(surveys.find(_.id.asString == in.surveyId))
            .someOrFail(
              s"No survey with id '${in.surveyId}'. Call list_surveys to see what exists.")
          siblings <- run(questionsOfQuery(lift(in.surveyId))).orDie
          written  <- writeQuestion(
            surveyId = survey_.id,
            surveyCategory = survey_.category,
            text = in.text,
            spec = in.question,
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

  val all: List[McpToolHandlerR[QuillSqlite]] = List(
    list,
    get,
    setQuestionActive,
    reorder,
    createSurvey,
    createQuestion)
end SurveyTools
