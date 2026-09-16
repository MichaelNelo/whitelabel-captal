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
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

// The case names are the wire values, lowercase and underscored, which is why they break Scala
// naming convention. `@caseName` is not an option: the JSON codec honours it but JsonSchemaGen
// does not — it builds the enum from the case id — so the schema would advertise "Profiling"
// while the decoder rejected anything but "profiling". A schema that lies is worse than an ugly
// identifier, since a caller following it fails every time.

enum SurveyCategory:
  case email
  case profiling
  case location
  case advertiser

object SurveyCategory:
  given Schema[SurveyCategory] = DeriveSchema.gen

enum QuestionKind:
  case radio
  case checkbox
  case dropdown
  case input
  case rating
  case numeric
  case date

object QuestionKind:
  given Schema[QuestionKind] = DeriveSchema.gen
  extension (k: QuestionKind)
    def takesOptions: Boolean =
      k == QuestionKind.radio || k == QuestionKind.checkbox || k == QuestionKind.dropdown

enum Hierarchy:
  case state
  case city
  case municipality
  case urbanization

object Hierarchy:
  given Schema[Hierarchy] = DeriveSchema.gen

/** Rule kinds as the operator names them. The stored JSON is built from these rather than taken
  * from the caller, so the only thing that can be wrong is pairing a kind with a question type that
  * does not accept it — which is checked. Hand-written rule JSON was the previous shape and its
  * failure mode was a rule that stored fine and never applied.
  */
enum RuleKind:
  case min_length
  case max_length
  case pattern
  case email
  case url
  case min_selections
  case max_selections
  case min
  case max

object RuleKind:
  given Schema[RuleKind] = DeriveSchema.gen

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
  final case class LocalizedValue(locale: String, value: String)
  final case class OptionView(
      id: String,
      displayOrder: Int,
      active: Boolean,
      text: List[LocalizedValue])
  final case class QuestionView(
      id: String,
      questionType: String,
      displayOrder: Int,
      required: Boolean,
      points: Int,
      active: Boolean,
      answers: Int,
      text: List[LocalizedValue],
      options: List[OptionView],
      rules: List[String])
  final case class SurveyView(
      id: String,
      category: String,
      name: Option[String],
      active: Boolean,
      questions: List[QuestionView])

  final case class ListIn(includeInactive: Boolean = false)
  final case class SurveySummary(
      id: String,
      category: String,
      name: Option[String],
      active: Boolean,
      activeQuestions: Int)
  final case class ListOut(surveys: List[SurveySummary])

  final case class GetIn(surveyId: String)

  final case class SetQuestionActiveIn(questionId: String, active: Boolean)
  final case class SetQuestionActiveOut(
      questionId: String,
      active: Boolean,
      answersPreserved: Int,
      remainingActive: Int)

  final case class ReorderIn(surveyId: String, orderedQuestionIds: List[String])
  final case class ReorderOut(surveyId: String, order: List[String])

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

  final case class TextIn(locale: String, value: String)
  final case class OptionIn(text: List[TextIn])
  final case class RuleIn(
      kind: RuleKind,
      @description(
        "The rule's bound, as text. Omit for email and url, which take none. min_length, max_length, min_selections and max_selections take a whole number; pattern takes a regex; min and max take a number for rating and numeric, and an ISO date for date questions.")
      value: Option[String])

  final case class CreateSurveyIn(
      category: SurveyCategory,
      @description("Label for operators. Not shown to guests; safe to omit.")
      name: Option[String],
      @description(
        "Required for advertiser surveys and rejected for the others: the video this survey follows. The survey inherits that video's location.")
      videoId: Option[String])
  final case class CreateSurveyOut(surveyId: String, category: String)

  final case class CreateQuestionIn(
      surveyId: String,
      questionType: QuestionKind,
      @description(
        "The wording, one entry per locale. Ask the operator for this; nothing else can supply it.")
      text: List[TextIn],
      @description("Helper text under the question. Omit when there is none.")
      description: Option[List[TextIn]],
      @description("Placeholder inside the input. Only meaningful for input questions.")
      placeholder: Option[List[TextIn]],
      @description(
        "Required for radio, checkbox and dropdown; rejected for the others. Each option carries its own wording per locale.")
      options: Option[List[OptionIn]],
      @description("Validation rules. Omit when the question needs none.")
      rules: Option[List[RuleIn]],
      @description("Whether an answer is mandatory. Defaults to true.")
      required: Option[Boolean],
      @description("Points awarded for answering. Defaults to 10, which is what every survey uses.")
      points: Option[Int],
      @description("Only for questions in a location survey; rejected elsewhere.")
      hierarchyLevel: Option[Hierarchy])
  final case class CreateQuestionOut(questionId: String, displayOrder: Int, optionIds: List[String])

  given Schema[TextIn] = DeriveSchema.gen
  given Schema[OptionIn] = DeriveSchema.gen
  given Schema[RuleIn] = DeriveSchema.gen
  given Schema[CreateSurveyIn] = DeriveSchema.gen
  given Schema[CreateSurveyOut] = DeriveSchema.gen
  given Schema[CreateQuestionIn] = DeriveSchema.gen
  given Schema[CreateQuestionOut] = DeriveSchema.gen

  /** Builds the stored rule JSON and checks the kind belongs to the question type.
    *
    * The config is constructed here rather than accepted from the caller, so its shape is always
    * the one `SurveyRepositoryQuill.decodeRules` expects. That matters because `decodeRules` does
    * `decode[R](config).toOption` and flat-maps: a rule it cannot read disappears at runtime with
    * no error, so a mismatch has to be caught before it is written or never at all.
    */
  private def ruleConfig(questionType: QuestionKind, rule: RuleIn): Either[String, String] =
    def needsValue(render: String => Either[String, String]): Either[String, String] = rule
      .value
      .toRight(s"The ${rule.kind} rule needs a value.")
      .flatMap(render)

    def wholeNumber(v: String): Either[String, String] = v
      .toIntOption
      .toRight(s"The ${rule.kind} rule needs a whole number, got '$v'.")
      .map(_.toString)

    def number(v: String): Either[String, String] = v
      .toDoubleOption
      .toRight(s"The ${rule.kind} rule needs a number, got '$v'.")
      .map(_ => v)

    def isoDate(v: String): Either[String, String] = scala
      .util
      .Try(java.time.LocalDate.parse(v))
      .toEither
      .left
      .map(_ => s"The ${rule.kind} rule needs an ISO date (yyyy-mm-dd), got '$v'.")
      .map(_ => s""""$v"""")

    def obj(kind: String, value: Option[String]): String =
      value match
        case Some(v) =>
          s"""{"type":"$kind","value":$v}"""
        case None =>
          s"""{"type":"$kind"}"""

    val accepted: Either[String, (String, Option[String])] =
      (questionType, rule.kind) match
        case (QuestionKind.input, RuleKind.min_length) =>
          needsValue(wholeNumber).map(v => ("min_length", Some(v)))
        case (QuestionKind.input, RuleKind.max_length) =>
          needsValue(wholeNumber).map(v => ("max_length", Some(v)))
        case (QuestionKind.input, RuleKind.pattern) =>
          needsValue(v => Right(s""""${v.replace("\\", "\\\\").replace("\"", "\\\"")}"""")).map(v =>
            ("pattern", Some(v)))
        case (QuestionKind.input, RuleKind.email) =>
          Right(("email", None))
        case (QuestionKind.input, RuleKind.url) =>
          Right(("url", None))
        case (QuestionKind.checkbox, RuleKind.min_selections) =>
          needsValue(wholeNumber).map(v => ("min_selections", Some(v)))
        case (QuestionKind.checkbox, RuleKind.max_selections) =>
          needsValue(wholeNumber).map(v => ("max_selections", Some(v)))
        case (QuestionKind.rating, RuleKind.min) =>
          needsValue(wholeNumber).map(v => ("min", Some(v)))
        case (QuestionKind.rating, RuleKind.max) =>
          needsValue(wholeNumber).map(v => ("max", Some(v)))
        case (QuestionKind.numeric, RuleKind.min) =>
          needsValue(number).map(v => ("min", Some(v)))
        case (QuestionKind.numeric, RuleKind.max) =>
          needsValue(number).map(v => ("max", Some(v)))
        case (QuestionKind.date, RuleKind.min) =>
          needsValue(isoDate).map(v => ("min", Some(v)))
        case (QuestionKind.date, RuleKind.max) =>
          needsValue(isoDate).map(v => ("max", Some(v)))
        case (qt, kind) =>
          Left(
            s"A ${qt
                .toString} question does not accept a $kind rule; it would be stored and silently ignored.")

    accepted.map(obj)
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

  val createQuestion: McpToolHandlerR[QuillSqlite] = McpTool("create_question")
    .description(
      "Add a question to a survey. Appended last; use reorder_questions to move it. Only the wording has to come from the operator — everything else has a sensible default or follows from the question type. Read skill://add-survey/SKILL.md for the locale constraints, which the schema cannot express.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateQuestionIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        val questionType = in.questionType.toString
        val descriptions = in.description.getOrElse(Nil)
        val placeholders = in.placeholder.getOrElse(Nil)
        val options = in.options.getOrElse(Nil)
        val rules = in.rules.getOrElse(Nil)
        val isRequired = in.required.getOrElse(true)
        val points = in.points.getOrElse(10)
        for
          surveys <- run(query[SurveyRow]).orDie
          survey_ <- ZIO
            .succeed(surveys.find(_.id.asString == in.surveyId))
            .someOrFail(
              s"No survey with id '${in.surveyId}'. Call list_surveys to see what exists.")
          _ <-
            ZIO.when(in.text.isEmpty)(
              ZIO.fail("A question needs text in at least one locale, or it cannot be rendered."))
          _ <- validateTexts(in.text, "text")
          _ <- validateTexts(descriptions, "description")
          _ <- validateTexts(placeholders, "placeholder")
          _ <-
            ZIO.when(in.questionType.takesOptions && options.isEmpty)(
              ZIO.fail(s"A $questionType question needs at least one option."))
          _ <-
            ZIO.when(!in.questionType.takesOptions && options.nonEmpty)(
              ZIO.fail(s"A $questionType question takes no options."))
          _ <-
            ZIO.foreachDiscard(options.zipWithIndex): (o, i) =>
              ZIO.when(o.text.isEmpty)(ZIO.fail(s"Option ${i + 1} has no text.")) *>
                validateTexts(o.text, s"option ${i + 1}")
          ruleConfigs <- ZIO.foreach(rules)(r => ZIO.fromEither(ruleConfig(in.questionType, r)))
          _           <-
            ZIO.when(in.hierarchyLevel.isDefined && survey_.category != "location")(
              ZIO.fail("hierarchyLevel only applies to questions in a location survey."))
          _        <- ZIO.when(points < 0)(ZIO.fail(s"Points must not be negative, got $points."))
          siblings <- run(questionsOfQuery(lift(in.surveyId))).orDie
          order      = siblings.map(_.displayOrder).maxOption.getOrElse(0) + 1
          now        = java.time.Instant.now.toString
          questionId = survey.question.Id.generate
          affected <-
            run(
              query[QuestionRow].insertValue(
                lift(
                  QuestionRow(
                    questionId,
                    survey_.id,
                    questionType,
                    points,
                    order,
                    in.hierarchyLevel.map(_.toString),
                    if isRequired then
                      1
                    else
                      0
                    ,
                    now,
                    1)))).orDie
          _         <- Writes.expectOne(affected, s"Creating question in survey '${in.surveyId}'")
          _         <- writeTexts(questionId.asString, in.text, now)
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
                  lift(
                    QuestionRuleRow(
                      UUID.randomUUID.toString,
                      questionId,
                      questionType,
                      config,
                      1)))).orDie.flatMap(Writes.expectOne(_, s"Creating rule $config"))
        yield CreateQuestionOut(questionId.asString, order, optionIds)
        end for

  val all: List[McpToolHandlerR[QuillSqlite]] = List(
    list,
    get,
    setQuestionActive,
    reorder,
    createSurvey,
    createQuestion)
end SurveyTools
