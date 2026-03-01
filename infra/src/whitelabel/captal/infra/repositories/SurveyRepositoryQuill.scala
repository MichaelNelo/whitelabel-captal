package whitelabel.captal.infra.repositories

import io.circe
import io.circe.parser.decode
import io.getquill.*
import whitelabel.captal.core.application.IdentificationSurveyType
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.infrastructure.SurveyRepository
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.{State, Survey}
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.session.SessionContext
import zio.*

object SurveyRepositoryQuill:
  final case class QuestionWithDetails(
      survey: SurveyRow,
      question: QuestionRow,
      option: Option[QuestionOptionRow],
      rule: Option[QuestionRuleRow],
      questionText: Option[LocalizedTextRow],
      questionDesc: Option[LocalizedTextRow],
      questionPlaceholder: Option[LocalizedTextRow],
      optionText: Option[LocalizedTextRow])

  // Helper para seleccionar el texto preferido (locale del usuario > inglés)
  private def selectPreferredText(
      texts: List[LocalizedTextRow],
      preferredLocale: String): Option[LocalizedText] = texts
    .sortBy(t =>
      if t.locale == preferredLocale then
        0
      else
        1)
    .headOption
    .map(t => LocalizedText(t.value, t.locale))

  // Query con JOINs para textos localizados
  inline def questionByIdQuery = quote:
    (
        surveyIdParam: survey.Id,
        questionIdParam: survey.question.Id,
        questionIdStr: String,
        questionDescIdStr: String,
        questionPlaceholderIdStr: String,
        localeParam: String) =>
      for
        surveyRow   <- query[SurveyRow].filter(_.id == surveyIdParam)
        questionRow <- query[QuestionRow].filter(row =>
          row.id == questionIdParam && row.surveyId == surveyIdParam)
        optionRow <- query[QuestionOptionRow].leftJoin(_.questionId == questionIdParam)
        ruleRow   <- query[QuestionRuleRow].leftJoin(_.questionId == questionIdParam)
        // JOIN para texto de pregunta
        questionText <- query[LocalizedTextRow]
          .filter(lt => lt.entityId == questionIdStr)
          .filter(lt => lt.locale == localeParam || lt.locale == "en")
          .leftJoin(_ => true)
        // JOIN para descripción de pregunta
        questionDesc <- query[LocalizedTextRow]
          .filter(lt => lt.entityId == questionDescIdStr)
          .filter(lt => lt.locale == localeParam || lt.locale == "en")
          .leftJoin(_ => true)
        // JOIN para placeholder de pregunta
        questionPlaceholder <- query[LocalizedTextRow]
          .filter(lt => lt.entityId == questionPlaceholderIdStr)
          .filter(lt => lt.locale == localeParam || lt.locale == "en")
          .leftJoin(_ => true)
        // JOIN para texto de opción - comparamos String con String directamente
        // El MappedEncoding convierte OptionId a String automáticamente
        optionText <- query[LocalizedTextRow].leftJoin(lt =>
          optionRow.exists(opt => lt.entityId == sql"${opt.id}".as[String]) &&
            (lt.locale == localeParam || lt.locale == "en"))
      yield QuestionWithDetails(
        surveyRow,
        questionRow,
        optionRow,
        ruleRow,
        questionText,
        questionDesc,
        questionPlaceholder,
        optionText)

  inline def firstEmailSurveyQuery = quote:
    query[SurveyRow]
      .filter(s => s.isActive == 1 && s.category == "email")
      .join(query[QuestionRow])
      .on((s, q) => s.id == q.surveyId)
      .sortBy((_, q) => q.displayOrder)(using Ord.asc)
      .map((s, q) => NextIdentificationSurveyRow(s.id, q.id, s.category))

  inline def nextIdentificationSurveyQuery = quote: (userIdParam: user.Id) =>
    val answeredQuestionIds = query[AnswerRow].filter(_.userId == userIdParam).map(_.questionId)
    val hasEmail =
      query[UserRow]
        .filter(userRow => userRow.id == userIdParam && userRow.email.isDefined)
        .nonEmpty

    // Check if all questions in each category are answered (pool exhausted)
    val hasExhaustedProfiling =
      query[SurveyRow]
        .filter(s => s.isActive == 1 && s.category == "profiling")
        .join(query[QuestionRow])
        .on((s, q) => s.id == q.surveyId)
        .filter((_, q) => !answeredQuestionIds.contains(q.id))
        .isEmpty

    val hasExhaustedLocation =
      query[SurveyRow]
        .filter(s => s.isActive == 1 && s.category == "location")
        .join(query[QuestionRow])
        .on((s, q) => s.id == q.surveyId)
        .filter((_, q) => !answeredQuestionIds.contains(q.id))
        .isEmpty

    query[SurveyRow]
      .filter: surveyRow =>
        surveyRow.isActive == 1 && (
          (surveyRow.category == "email" && !hasEmail) ||
            (surveyRow.category == "profiling" && hasEmail && !hasExhaustedProfiling) || (
              surveyRow.category == "location" && hasEmail && hasExhaustedProfiling &&
                !hasExhaustedLocation
            )
        )
      .join(query[QuestionRow])
      .on: (surveyRow, questionRow) =>
        surveyRow.id == questionRow.surveyId && !answeredQuestionIds.contains(questionRow.id)
      .sortBy((_, questionRow) => questionRow.displayOrder)(using Ord.asc)
      .map((surveyRow, questionRow) =>
        NextIdentificationSurveyRow(surveyRow.id, questionRow.id, surveyRow.category))

  def apply(quill: QuillSqlite, ctx: SessionContext): SurveyRepository[Task] =
    new SurveyRepository[Task]:
      import quill.*

      def findAssignedEmailSurvey(): Task[Option[Survey[State.WithEmailQuestion]]] = ctx
        .get
        .flatMap:
          case Some(sessionData) =>
            sessionData.currentQuestion match
              case Some(question) =>
                findQuestionWithCategory(question.surveyId, question.questionId, "email")(
                  State.WithEmailQuestion.apply)
              case None =>
                ZIO.none
          case None =>
            ZIO.none

      def findWithProfilingQuestion(
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Option[Survey[State.WithProfilingQuestion]]] =
        findQuestionWithCategory(surveyId, questionId, "profiling")(
          State.WithProfilingQuestion.apply)

      def findWithLocationQuestion(
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Option[Survey[State.WithLocationQuestion]]] =
        (
          for
            sessionData <- ctx.getOrFail
            questionIdStr = questionId.asString
            rows <- run(
              questionByIdQuery(
                lift(surveyId),
                lift(questionId),
                lift(questionIdStr),
                lift(questionIdStr + "_desc"),
                lift(questionIdStr + "_placeholder"),
                lift(sessionData.locale)))
          yield
            val result: Option[Survey[State.WithLocationQuestion]] =
              for
                first <- rows.headOption
                if first.survey.category == "location"
                question <- buildQuestionToAnswer(first.question, rows, sessionData.locale)
                hierarchyLevel = first
                  .question
                  .hierarchyLevel
                  .flatMap(parseHierarchyLevel)
                  .getOrElse(HierarchyLevel.State)
              yield Survey(surveyId, State.WithLocationQuestion(question, hierarchyLevel))
            result
        ).orDie

      def findWithAdvertiserQuestion(
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Option[Survey[State.WithAdvertiserQuestion]]] =
        (
          for
            sessionData <- ctx.getOrFail
            questionIdStr = questionId.asString
            rows <- run(
              questionByIdQuery(
                lift(surveyId),
                lift(questionId),
                lift(questionIdStr),
                lift(questionIdStr + "_desc"),
                lift(questionIdStr + "_placeholder"),
                lift(sessionData.locale)))
          yield
            val result: Option[Survey[State.WithAdvertiserQuestion]] =
              for
                first <- rows.headOption
                if first.survey.category == "advertiser"
                question     <- buildQuestionToAnswer(first.question, rows, sessionData.locale)
                advertiserId <- first.survey.advertiserId.flatMap(survey.AdvertiserId.fromString)
              yield Survey(surveyId, State.WithAdvertiserQuestion(advertiserId, question))
            result
        ).orDie

      def findNextIdentificationSurvey(): Task[Option[NextIdentificationSurvey]] = ctx
        .get
        .flatMap:
          case Some(sessionData) =>
            sessionData.userId match
              case Some(userId) =>
                run(nextIdentificationSurveyQuery(lift(userId)).take(1))
                  .flatMap(_.headOption.fold(ZIO.none)(fetchQuestionDetails))
                  .orDie
              case None =>
                run(firstEmailSurveyQuery.take(1))
                  .flatMap(_.headOption.fold(ZIO.none)(fetchQuestionDetails))
                  .orDie
          case None =>
            run(firstEmailSurveyQuery.take(1))
              .flatMap(_.headOption.fold(ZIO.none)(fetchQuestionDetails))
              .orDie

      private def fetchQuestionDetails(
          row: NextIdentificationSurveyRow): Task[Option[NextIdentificationSurvey]] =
        for
          sessionData <- ctx.getOrFail
          questionIdStr = row.questionId.asString
          rows <- run(
            questionByIdQuery(
              lift(row.surveyId),
              lift(row.questionId),
              lift(questionIdStr),
              lift(questionIdStr + "_desc"),
              lift(questionIdStr + "_placeholder"),
              lift(sessionData.locale)))
        yield
          for
            first    <- rows.headOption
            question <- buildQuestionToAnswer(first.question, rows, sessionData.locale)
          yield toNextIdentificationSurvey(row, question)

      private def findQuestionWithCategory[S <: State](
          surveyId: survey.Id,
          questionId: survey.question.Id,
          questionCategory: String)(stateFactory: QuestionToAnswer => S): Task[Option[Survey[S]]] =
        (
          for
            sessionData <- ctx.getOrFail
            questionIdStr = questionId.asString
            rows <- run(
              questionByIdQuery(
                lift(surveyId),
                lift(questionId),
                lift(questionIdStr),
                lift(questionIdStr + "_desc"),
                lift(questionIdStr + "_placeholder"),
                lift(sessionData.locale)))
          yield
            for
              first <- rows.headOption
              if first.survey.category == questionCategory
              question <- buildQuestionToAnswer(first.question, rows, sessionData.locale)
            yield Survey(surveyId, stateFactory(question))
        ).orDie

  private def buildQuestionToAnswer(
      questionRow: QuestionRow,
      rows: List[QuestionWithDetails],
      locale: String): Option[QuestionToAnswer] =
    // Obtener textos de pregunta (preferir locale del usuario)
    val questionTexts = rows.flatMap(_.questionText).distinctBy(_.id)
    val questionDescs = rows.flatMap(_.questionDesc).distinctBy(_.id)
    val questionPlaceholders = rows.flatMap(_.questionPlaceholder).distinctBy(_.id)

    // Construir opciones con sus textos
    val optionRows = rows.flatMap(_.option).distinctBy(_.id)
    val optionTextsMap: Map[String, List[LocalizedTextRow]] =
      rows
        .flatMap(r => r.option.map(_.id.asString).zip(r.optionText))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap
    val options = optionRows.map(opt => toQuestionOption(opt, optionTextsMap, locale))

    val rules = rows.flatMap(_.rule).distinctBy(_.id)
    val questionType = parseQuestionType(questionRow.questionType, options, rules)
    val commonRules =
      if questionRow.isRequired == 1 then
        List(CommonRule.Required)
      else
        Nil

    val questionText = selectPreferredText(questionTexts, locale)
    val questionDesc = selectPreferredText(questionDescs, locale)
    val questionPlaceholder = selectPreferredText(questionPlaceholders, locale)

    questionText.map: text =>
      QuestionToAnswer(
        id = questionRow.id,
        text = text,
        description = questionDesc,
        placeholder = questionPlaceholder,
        questionType = questionType,
        commonRules = commonRules,
        pointsAwarded = questionRow.pointsAwarded
      )
  end buildQuestionToAnswer

  private def toQuestionOption(
      row: QuestionOptionRow,
      textsMap: Map[String, List[LocalizedTextRow]],
      locale: String): QuestionOption =
    val texts = textsMap.getOrElse(row.id.asString, Nil)
    val text = selectPreferredText(texts, locale).getOrElse(
      LocalizedText(s"[${row.id.asString}]", locale))
    QuestionOption(row.id, text, row.displayOrder, row.parentOptionId)

  private def parseQuestionType(
      typeStr: String,
      options: List[QuestionOption],
      rules: List[QuestionRuleRow]): QuestionType =
    typeStr match
      case "radio" =>
        QuestionType.Radio(options)
      case "checkbox" =>
        QuestionType.Checkbox(options, decodeRules[SelectionRule](rules))
      case "select" =>
        QuestionType.Select(options)
      case "input" =>
        QuestionType.Input(decodeRules[TextRule](rules))
      case "rating" =>
        QuestionType.Rating(decodeRules[RangeRule[Int]](rules))
      case "numeric" =>
        QuestionType.Numeric(decodeRules[RangeRule[BigDecimal]](rules))
      case "date" =>
        QuestionType.Date(decodeRules[RangeRule[java.time.LocalDate]](rules))
      case _ =>
        QuestionType.Input(Nil)

  private def decodeRules[R: circe.Decoder](rules: List[QuestionRuleRow]): List[R] = rules.flatMap(
    r => decode[R](r.ruleConfig).toOption)

  private def parseHierarchyLevel(level: String): Option[HierarchyLevel] =
    decode[HierarchyLevel](s"\"$level\"").toOption

  private def toNextIdentificationSurvey(
      row: NextIdentificationSurveyRow,
      question: QuestionToAnswer): NextIdentificationSurvey =
    val surveyType =
      row.category match
        case "email" =>
          IdentificationSurveyType.Email
        case "profiling" =>
          IdentificationSurveyType.Profiling
        case "location" =>
          IdentificationSurveyType.Location

    NextIdentificationSurvey(row.surveyId, surveyType, question)

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, SurveyRepository[Task]] = ZLayer
    .fromFunction(apply)
end SurveyRepositoryQuill
