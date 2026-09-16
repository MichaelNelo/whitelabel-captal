package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
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
      "Retire a question or bring it back. Never deletes: answers reference questions, so a retired question keeps resolving for everything already recorded. Reports how many answers that is.")
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

  val all: List[McpToolHandlerR[QuillSqlite]] = List(list, get, setQuestionActive, reorder)
end SurveyTools
