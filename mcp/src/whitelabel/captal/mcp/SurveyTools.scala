package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.survey
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{
  AnswerRow,
  LocalizedTextRow,
  QuestionOptionRow,
  QuestionRow,
  QuestionRuleRow,
  SurveyRow
}
import zio.*
import zio.schema.{DeriveSchema, Schema}

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
  // ───────────────────────────────────────────────────────────────────────────

  private val Categories = Set("email", "profiling", "location", "advertiser")
  private val QuestionTypes = Set(
    "radio",
    "checkbox",
    "dropdown",
    "input",
    "rating",
    "numeric",
    "date")
  private val ChoiceTypes = Set("radio", "checkbox", "dropdown")
  private val HierarchyLevels = Set("state", "city", "municipality", "urbanization")

  final case class TextIn(locale: String, value: String)
  final case class OptionIn(text: List[TextIn])

  final case class CreateSurveyIn(
      category: String,
      name: Option[String],
      videoId: Option[String],
      locationId: Option[String])
  final case class CreateSurveyOut(surveyId: String, category: String)

  final case class CreateQuestionIn(
      surveyId: String,
      questionType: String,
      text: List[TextIn],
      description: List[TextIn],
      placeholder: List[TextIn],
      options: List[OptionIn],
      rules: List[String],
      required: Boolean,
      points: Int,
      hierarchyLevel: Option[String])
  final case class CreateQuestionOut(questionId: String, displayOrder: Int, optionIds: List[String])

  given Schema[TextIn] = DeriveSchema.gen
  given Schema[OptionIn] = DeriveSchema.gen
  given Schema[CreateSurveyIn] = DeriveSchema.gen
  given Schema[CreateSurveyOut] = DeriveSchema.gen
  given Schema[CreateQuestionIn] = DeriveSchema.gen
  given Schema[CreateQuestionOut] = DeriveSchema.gen

  /** Rejects a rule that would be stored and then never applied.
    *
    * `SurveyRepositoryQuill.decodeRules` does `decode[R](config).toOption` and flat-maps, so a rule
    * whose JSON does not match the ADT implied by the question type disappears at runtime with no
    * error anywhere. Validating here is the only point where that is visible.
    */
  private def validateRule(questionType: String, config: String): Either[String, Unit] =
    import io.circe.parser.decode
    import whitelabel.captal.core.survey.question.*
    import whitelabel.captal.core.survey.question.codecs.given
    def check[A: io.circe.Decoder](shape: String) = decode[A](config)
      .left
      .map(e =>
        s"Rule $config is not a valid $shape rule for a $questionType question: ${e.getMessage}")
      .map(_ => ())
    questionType match
      case "input" =>
        check[TextRule]("text")
      case "checkbox" =>
        check[SelectionRule]("selection")
      case "rating" =>
        check[RangeRule[Int]]("integer range")
      case "numeric" =>
        check[RangeRule[BigDecimal]]("numeric range")
      case "date" =>
        check[RangeRule[java.time.LocalDate]]("date range")
      case other =>
        Left(
          s"A $other question takes no validation rules; it would be stored and silently ignored.")
  end validateRule

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
      "Create a survey. Category is one of email, profiling, location or advertiser. Advertiser surveys must name the video they follow; the others are picked by category during identification. Read skill://add-survey/SKILL.md first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateSurveyIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          _ <-
            ZIO.when(!Categories.contains(in.category))(
              ZIO.fail(
                s"Category must be one of ${Categories.toList.sorted.mkString(", ")}, got '${in
                    .category}'."))
          // The video is what findNextAdvertiserSurvey matches on; without it the survey exists but
          // is unreachable.
          _ <-
            ZIO.when(in.category == "advertiser" && in.videoId.isEmpty)(
              ZIO.fail("An advertiser survey needs the videoId of the video it follows."))
          _ <-
            ZIO.when(in.category != "advertiser" && in.videoId.isDefined)(
              ZIO.fail(s"Only advertiser surveys attach to a video; this one is '${in.category}'."))
          existing <- run(query[SurveyRow]).orDie
          // Identification surveys are chosen by category alone, so a second active one of the same
          // category makes which gets served depend on display_order across unrelated surveys.
          clashes = existing.filter(s =>
            s.isActive == 1 && s.category == in.category && s.category != "advertiser" &&
              s.locationId == in.locationId)
          _ <-
            ZIO.when(clashes.nonEmpty)(
              ZIO.fail(
                s"An active '${in.category}' survey already exists here (id ${clashes
                    .head
                    .id
                    .asString}). Identification surveys are picked by category, so two would be ambiguous."))
          now = java.time.Instant.now.toString
          id  = survey.Id.generate
          affected <-
            run(
              query[SurveyRow].insertValue(
                lift(SurveyRow(id, in.category, None, in.videoId, in.locationId, 1, now, in.name))))
              .orDie
          _ <- Writes.expectOne(affected, s"Creating ${in.category} survey")
        yield CreateSurveyOut(id.asString, in.category)
        end for

  val createQuestion: McpToolHandlerR[QuillSqlite] = McpTool("create_question")
    .description(
      "Add a question to a survey, with its texts, options and validation rules. Appended last; use reorder_questions to move it. Choice questions (radio, checkbox, dropdown) require options; the others must not have any. Read skill://add-survey/SKILL.md for the rule and locale constraints, which this schema cannot express.")
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
          _ <-
            ZIO.when(!QuestionTypes.contains(in.questionType))(
              ZIO.fail(
                s"Question type must be one of ${QuestionTypes
                    .toList
                    .sorted
                    .mkString(", ")}, got '${in.questionType}'."))
          _ <-
            ZIO.when(in.text.isEmpty)(
              ZIO.fail("A question needs text in at least one locale, or it cannot be rendered."))
          _ <- validateTexts(in.text, "text")
          _ <- validateTexts(in.description, "description")
          _ <- validateTexts(in.placeholder, "placeholder")
          isChoice = ChoiceTypes.contains(in.questionType)
          _ <-
            ZIO.when(isChoice && in.options.isEmpty)(
              ZIO.fail(s"A ${in.questionType} question needs at least one option."))
          _ <-
            ZIO.when(!isChoice && in.options.nonEmpty)(
              ZIO.fail(s"A ${in.questionType} question takes no options."))
          _ <-
            ZIO.foreachDiscard(in.options.zipWithIndex): (o, i) =>
              ZIO.when(o.text.isEmpty)(ZIO.fail(s"Option ${i + 1} has no text.")) *>
                validateTexts(o.text, s"option ${i + 1}")
          _ <- ZIO.foreachDiscard(in.rules)(r => ZIO.fromEither(validateRule(in.questionType, r)))
          _ <-
            ZIO.when(in.hierarchyLevel.isDefined && survey_.category != "location")(
              ZIO.fail("hierarchyLevel only applies to questions in a location survey."))
          _ <-
            ZIO.when(in.hierarchyLevel.exists(h => !HierarchyLevels.contains(h)))(
              ZIO.fail(
                s"hierarchyLevel must be one of ${HierarchyLevels.toList.sorted.mkString(", ")}."))
          _ <- ZIO.when(in.points < 0)(ZIO.fail(s"Points must not be negative, got ${in.points}."))
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
                    in.questionType,
                    in.points,
                    order,
                    in.hierarchyLevel,
                    if in.required then
                      1
                    else
                      0
                    ,
                    now,
                    1)))).orDie
          _         <- Writes.expectOne(affected, s"Creating question in survey '${in.surveyId}'")
          _         <- writeTexts(questionId.asString, in.text, now)
          _         <- writeTexts(s"${questionId.asString}_desc", in.description, now)
          _         <- writeTexts(s"${questionId.asString}_placeholder", in.placeholder, now)
          optionIds <-
            ZIO.foreach(in.options.zipWithIndex): (o, i) =>
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
            ZIO.foreachDiscard(in.rules): config =>
              run(
                query[QuestionRuleRow].insertValue(
                  lift(
                    QuestionRuleRow(
                      UUID.randomUUID.toString,
                      questionId,
                      in.questionType,
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
