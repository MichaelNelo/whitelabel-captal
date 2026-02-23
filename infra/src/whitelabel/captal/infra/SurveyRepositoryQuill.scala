package whitelabel.captal.infra

import io.circe
import io.circe.parser.decode
import io.getquill.*
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.jdbczio.Quill
import izumi.reflect.Tag
import whitelabel.captal.core.application.commands.{
  IdentificationSurveyType,
  NextIdentificationSurvey
}
import whitelabel.captal.core.infrastructure.SurveyRepository
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.{State, Survey}
import whitelabel.captal.core.{survey, user}
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

  inline def surveyWithQuestionsQuery = quote: (surveyId: String) =>
    for
      survey   <- query[SurveyRow].filter(_.id == surveyId)
      question <- query[QuestionRow].join(_.surveyId == surveyId)
      option   <- query[QuestionOptionRow].leftJoin(_.questionId == question.id)
      rule     <- query[QuestionRuleRow].leftJoin(_.questionId == question.id)
    yield QuestionWithDetails(survey, question, option, rule)

  inline def nextIdentificationSurveyQuery = quote: (userId: String) =>
    val completedSurveyCategories = query[UserSurveyProgressRow]
      .filter(p => p.userId == userId && p.completedAt.isDefined)
      .join(query[SurveyRow])
      .on((p, s) => p.surveyId == s.id)
      .map(_._2.category)

    val answeredQuestionIds = query[AnswerRow].filter(_.userId == userId).map(_.questionId)

    val hasEmail = query[UserRow].filter(u => u.id == userId && u.email.isDefined).nonEmpty
    val hasCompletedEmail = completedSurveyCategories.filter(_ == "email").nonEmpty
    val hasCompletedProfiling = completedSurveyCategories.filter(_ == "profiling").nonEmpty

    for
      survey <- query[SurveyRow].filter: s =>
        s.isActive == 1 && (
          (s.category == "email" && !hasEmail && !hasCompletedEmail) ||
            (s.category == "profiling" && hasEmail && !hasCompletedProfiling) || (
              s.category == "location" && hasEmail && hasCompletedProfiling &&
                !completedSurveyCategories.filter(_ == "location").nonEmpty
            )
        )
      question <- query[QuestionRow]
        .filter(q => q.surveyId == survey.id && !answeredQuestionIds.contains(q.id))
        .sortBy(_.displayOrder)(using Ord.asc)
        .take(1)
    yield NextIdentificationSurveyRow(survey.id, question.id, survey.category)

  def apply[D <: SqlIdiom, N <: NamingStrategy](quill: Quill[D, N]): SurveyRepository[Task] =
    new SurveyRepository[Task]:
      import quill.*

      def findById(id: survey.Id): Task[Option[Survey[State]]] =
        run(surveyWithQuestionsQuery(lift(id.asString)))
          .map: rows =>
            for
              first    <- rows.headOption
              question <- buildQuestionToAnswer(first.question, rows)
              state    <- buildState(first.survey, question)
            yield Survey(id, state)
          .orDie

      def findWithEmailQuestion(
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Option[Survey[State.WithEmailQuestion]]] =
        findQuestionWithCategory(surveyId, questionId, "email")(State.WithEmailQuestion.apply)

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

      def findNextIdentificationSurvey(userId: user.Id): Task[Option[NextIdentificationSurvey]] =
        run(nextIdentificationSurveyQuery(lift(userId.asString)).take(1))
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

  private def buildState(row: SurveyRow, question: QuestionToAnswer): Option[State] =
    row.category match
      case "email" =>
        Some(State.WithEmailQuestion(question))
      case "profiling" =>
        Some(State.WithProfilingQuestion(question))
      case "location" =>
        parseHierarchyLevel(row.advertiserId.getOrElse("state")).map(
          State.WithLocationQuestion(question, _))
      case "advertiser" =>
        row
          .advertiserId
          .flatMap(survey.AdvertiserId.fromString)
          .map(State.WithAdvertiserQuestion(_, question))
      case _ =>
        None

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

  def layer[D <: SqlIdiom: Tag, N <: NamingStrategy: Tag]
      : ZLayer[Quill[D, N], Nothing, SurveyRepository[Task]] = ZLayer.fromFunction(apply[D, N])
end SurveyRepositoryQuill
