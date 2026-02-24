package whitelabel.captal.infra

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
import whitelabel.captal.infra.QuillSchema.given
import zio.*

object SurveyRepositoryQuill:
  final case class QuestionWithDetails(
      survey: SurveyRow,
      question: QuestionRow,
      option: Option[QuestionOptionRow],
      rule: Option[QuestionRuleRow])

  inline def questionByIdQuery = quote: (surveyId: String, questionId: String) =>
    for
      survey   <- query[SurveyRow].filter(_.id == surveyId)
      question <- query[QuestionRow].filter(q => q.id == questionId && q.surveyId == surveyId)
      option   <- query[QuestionOptionRow].leftJoin(_.questionId == questionId)
      rule     <- query[QuestionRuleRow].leftJoin(_.questionId == questionId)
    yield QuestionWithDetails(survey, question, option, rule)

  inline def firstEmailSurveyQuery = quote:
    query[SurveyRow]
      .filter(s => s.isActive == 1 && s.category == "email")
      .join(query[QuestionRow])
      .on((s, q) => s.id == q.surveyId)
      .sortBy((_, q) => q.displayOrder)(using Ord.asc)
      .map((s, q) => NextIdentificationSurveyRow(s.id, q.id, s.category))

  // Helper to check if user has completed a survey of given category
  private inline def hasCompletedCategoryQuery(userId: String, category: String) =
    query[UserSurveyProgressRow]
      .filter(p =>
        p.userId == userId &&
          p.completedAt.isDefined &&
          query[SurveyRow]
            .filter(s => s.id == p.surveyId && s.category == category)
            .nonEmpty)
      .nonEmpty

  inline def nextIdentificationSurveyQuery = quote: (userId: String) =>
    val answeredQuestionIds = query[AnswerRow].filter(_.userId == userId).map(_.questionId)
    val hasEmail = query[UserRow].filter(u => u.id == userId && u.email.isDefined).nonEmpty
    val hasCompletedEmail = hasCompletedCategoryQuery(userId, "email")
    val hasCompletedProfiling = hasCompletedCategoryQuery(userId, "profiling")
    val hasCompletedLocation = hasCompletedCategoryQuery(userId, "location")

    query[SurveyRow]
      .filter: s =>
        s.isActive == 1 && (
          (s.category == "email" && !hasEmail && !hasCompletedEmail) ||
            (s.category == "profiling" && hasEmail && !hasCompletedProfiling) ||
            (s.category == "location" && hasEmail && hasCompletedProfiling && !hasCompletedLocation)
        )
      .join(query[QuestionRow])
      .on: (s, q) =>
        s.id == q.surveyId && !answeredQuestionIds.contains(q.id)
      .sortBy((_, q) => q.displayOrder)(using Ord.asc)
      .map((s, q) => NextIdentificationSurveyRow(s.id, q.id, s.category))

  def apply(quill: QuillSqlite, ctx: SessionContext): SurveyRepository[Task] =
    new SurveyRepository[Task]:
      import quill.*

      def findAssignedEmailSurvey(): Task[Option[Survey[State.WithEmailQuestion]]] = ctx
        .get
        .flatMap:
          case Some(sessionData) =>
            (sessionData.currentSurveyId, sessionData.currentQuestionId) match
              case (Some(surveyId), Some(questionId)) =>
                findQuestionWithCategory(surveyId, questionId, "email")(
                  State.WithEmailQuestion.apply)
              case _ =>
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
        run(questionByIdQuery(lift(surveyId.asString), lift(questionId.asString)))
          .map: rows =>
            val result: Option[Survey[State.WithLocationQuestion]] =
              for
                first <- rows.headOption
                if first.survey.category == "location"
                question <- buildQuestionToAnswer(first.question, rows)
                hierarchyLevel = first
                  .question
                  .hierarchyLevel
                  .flatMap(parseHierarchyLevel)
                  .getOrElse(HierarchyLevel.State)
              yield Survey(surveyId, State.WithLocationQuestion(question, hierarchyLevel))
            result
          .orDie

      def findWithAdvertiserQuestion(
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Option[Survey[State.WithAdvertiserQuestion]]] =
        run(questionByIdQuery(lift(surveyId.asString), lift(questionId.asString)))
          .map: rows =>
            val result: Option[Survey[State.WithAdvertiserQuestion]] =
              for
                first <- rows.headOption
                if first.survey.category == "advertiser"
                question     <- buildQuestionToAnswer(first.question, rows)
                advertiserId <- first.survey.advertiserId.flatMap(survey.AdvertiserId.fromString)
              yield Survey(surveyId, State.WithAdvertiserQuestion(advertiserId, question))
            result
          .orDie

      def findNextIdentificationSurvey(): Task[Option[NextIdentificationSurvey]] = ctx
        .get
        .flatMap:
          case Some(sessionData) =>
            sessionData.userId match
              case Some(userId) =>
                run(nextIdentificationSurveyQuery(lift(userId.asString)).take(1))
                  .map(_.headOption.map(toNextIdentificationSurvey))
                  .orDie
              case None =>
                run(firstEmailSurveyQuery.take(1))
                  .map(_.headOption.map(toNextIdentificationSurvey))
                  .orDie
          case None =>
            run(firstEmailSurveyQuery.take(1))
              .map(_.headOption.map(toNextIdentificationSurvey))
              .orDie

      private def findQuestionWithCategory[S <: State](
          surveyId: survey.Id,
          questionId: survey.question.Id,
          questionCategory: String)(stateFactory: QuestionToAnswer => S): Task[Option[Survey[S]]] =
        run(questionByIdQuery(lift(surveyId.asString), lift(questionId.asString)))
          .map: rows =>
            for
              first <- rows.headOption
              if first.survey.category == questionCategory
              question <- buildQuestionToAnswer(first.question, rows)
            yield Survey(surveyId, stateFactory(question))
          .orDie

  private def buildQuestionToAnswer(
      questionRow: QuestionRow,
      rows: List[QuestionWithDetails]): Option[QuestionToAnswer] =
    for questionId <- Id.fromString(questionRow.id)
    yield
      val options = rows.flatMap(_.option).distinctBy(_.id).flatMap(toQuestionOption)
      val rules = rows.flatMap(_.rule).distinctBy(_.id)
      val questionType = parseQuestionType(questionRow.questionType, options, rules)
      val commonRules =
        if questionRow.isRequired == 1 then
          List(CommonRule.Required)
        else
          Nil

      QuestionToAnswer(
        id = questionId,
        text = LocalizedText(questionRow.textContent, questionRow.textLocale),
        description = questionRow
          .descriptionContent
          .map(c =>
            LocalizedText(c, questionRow.descriptionLocale.getOrElse(questionRow.textLocale))),
        questionType = questionType,
        commonRules = commonRules,
        pointsAwarded = questionRow.pointsAwarded
      )

  private def toQuestionOption(row: QuestionOptionRow): Option[QuestionOption] =
    for optionId <- OptionId.fromString(row.id)
    yield QuestionOption(
      optionId,
      LocalizedText(row.textContent, row.textLocale),
      row.displayOrder,
      row.parentOptionId.flatMap(OptionId.fromString))

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
      row: NextIdentificationSurveyRow): NextIdentificationSurvey =
    val surveyType =
      row.category match
        case "email" =>
          IdentificationSurveyType.Email
        case "profiling" =>
          IdentificationSurveyType.Profiling
        case "location" =>
          IdentificationSurveyType.Location

    NextIdentificationSurvey(
      survey.Id.unsafe(row.surveyId),
      survey.question.Id.unsafe(row.questionId),
      surveyType)

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, SurveyRepository[Task]] =
    ZLayer.fromFunction(apply)
end SurveyRepositoryQuill
