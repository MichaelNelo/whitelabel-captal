package whitelabel.captal.platform.repositories
import io.circe.parser.decode
import io.getquill.*
import whitelabel.captal.core.application.IdentificationSurveyType
import whitelabel.captal.core.application.commands.{NextAdvertiserSurvey, NextIdentificationSurvey}
import whitelabel.captal.core.infrastructure.SurveyRepository
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.{State, Survey}
import whitelabel.captal.core.{survey, user, video}
import whitelabel.captal.platform.*
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionContext
import whitelabel.captal.platform.surveySchemas.given
import zio.*
import zio.schema.Schema

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

  // Picks the preferred text: the user's locale, falling back to English.
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

  // The query, joining in the localized texts.
  //
  // Every child of the question filters on is_active too. Deactivating an option, a rule or a
  // translation has to remove it from the portal, not just from a backoffice listing.
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
          row.id == questionIdParam && row.surveyId == surveyIdParam && row.isActive == 1)
        optionRow <- query[QuestionOptionRow].leftJoin(opt =>
          opt.questionId == questionIdParam && opt.isActive == 1)
        ruleRow <- query[QuestionRuleRow].leftJoin(rule =>
          rule.questionId == questionIdParam && rule.isActive == 1)
        // JOIN para texto de pregunta
        questionText <- query[LocalizedTextRow]
          .filter(lt => lt.entityId == questionIdStr && lt.isActive == 1)
          .filter(lt => lt.locale == localeParam || lt.locale == "en")
          .leftJoin(_ => true)
        // Joins the question's description.
        questionDesc <- query[LocalizedTextRow]
          .filter(lt => lt.entityId == questionDescIdStr && lt.isActive == 1)
          .filter(lt => lt.locale == localeParam || lt.locale == "en")
          .leftJoin(_ => true)
        // JOIN para placeholder de pregunta
        questionPlaceholder <- query[LocalizedTextRow]
          .filter(lt => lt.entityId == questionPlaceholderIdStr && lt.isActive == 1)
          .filter(lt => lt.locale == localeParam || lt.locale == "en")
          .leftJoin(_ => true)
        // Joins the option's text, comparing String against String directly.
        // El MappedEncoding convierte OptionId a String automáticamente
        optionText <- query[LocalizedTextRow].leftJoin(lt =>
          lt.isActive == 1 && optionRow.exists(opt => lt.entityId == sql"${opt.id}".as[String]) &&
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
      .filter(s => s.isActive == 1 && s.category == SurveyCategory.Email)
      .join(query[QuestionRow].filter(_.isActive == 1))
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
        .filter(s => s.isActive == 1 && s.category == SurveyCategory.Profiling)
        .join(query[QuestionRow].filter(_.isActive == 1))
        .on((s, q) => s.id == q.surveyId)
        .filter((_, q) => !answeredQuestionIds.contains(q.id))
        .isEmpty

    val hasExhaustedLocation =
      query[SurveyRow]
        .filter(s => s.isActive == 1 && s.category == SurveyCategory.Location)
        .join(query[QuestionRow].filter(_.isActive == 1))
        .on((s, q) => s.id == q.surveyId)
        .filter((_, q) => !answeredQuestionIds.contains(q.id))
        .isEmpty

    query[SurveyRow]
      .filter: surveyRow =>
        surveyRow.isActive == 1 && (
          (surveyRow.category == SurveyCategory.Email && !hasEmail) || (
            surveyRow.category == SurveyCategory.Profiling && hasEmail && !hasExhaustedProfiling
          ) || (
            surveyRow.category == SurveyCategory.Location && hasEmail && hasExhaustedProfiling &&
              !hasExhaustedLocation
          )
        )
      .join(query[QuestionRow].filter(_.isActive == 1))
      .on: (surveyRow, questionRow) =>
        surveyRow.id == questionRow.surveyId && !answeredQuestionIds.contains(questionRow.id)
      .sortBy((_, questionRow) => questionRow.displayOrder)(using Ord.asc)
      .map((surveyRow, questionRow) =>
        NextIdentificationSurveyRow(surveyRow.id, questionRow.id, surveyRow.category))

  /** The next unanswered question of the survey that follows one video.
    *
    * Scoped by `video_id` alone. It used to also require `category = 'advertiser'`, which is
    * redundant — only a video survey carries a `video_id`, and the three identification categories
    * are created with none — and wrong, because it reads as though the video's *type* decided
    * whether its survey is reachable. A promo's survey is a survey like any other; what makes this
    * query the right one is the video, not who the video belongs to.
    */
  inline def nextAdvertiserSurveyQuery = quote: (videoIdParam: String, userIdParam: user.Id) =>
    val answeredByUser = query[AnswerRow].filter(_.userId == userIdParam).map(_.questionId)
    query[SurveyRow]
      .filter(s => s.isActive == 1 && s.videoId.contains(videoIdParam))
      .join(query[QuestionRow].filter(_.isActive == 1))
      .on((s, q) => s.id == q.surveyId)
      .filter((_, q) => !answeredByUser.contains(q.id))
      .sortBy((_, q) => q.displayOrder)(using Ord.asc)
      .map((s, q) => NextIdentificationSurveyRow(s.id, q.id, s.category))

  def apply(quill: QuillSqlite, ctx: SessionContext): SurveyRepository[Task] =
    new SurveyRepository[Task]:
      import quill.*

      def findAssignedEmailSurvey(): Task[Option[Survey[State.WithEmailQuestion]]] = ctx
        .get
        .flatMap:
          case Some(sessionData) =>
            sessionData.currentQuestion match
              case Some(question) =>
                findQuestionWithCategory(
                  question.surveyId,
                  question.questionId,
                  SurveyCategory.Email)(State.WithEmailQuestion.apply)
              case None =>
                ZIO.none
          case None =>
            ZIO.none

      def findWithProfilingQuestion(
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Option[Survey[State.WithProfilingQuestion]]] =
        findQuestionWithCategory(surveyId, questionId, SurveyCategory.Profiling)(
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
                if first.survey.category == SurveyCategory.Location
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
                // Carrying a video is what makes this a video survey — the same condition
                // `nextAdvertiserSurveyQuery` selects on. Checking the category instead would let
                // the two disagree: a survey offered by that query and refused here answers
                // nothing and reports no reason.
                if first.survey.videoId.isDefined
                question <- buildQuestionToAnswer(first.question, rows, sessionData.locale)
              yield Survey(
                surveyId,
                State.WithAdvertiserQuestion(advertiserIdOf(first.survey), question))
            result
        ).orDie

      def findNextAdvertiserSurvey(videoId: video.Id): Task[Option[NextAdvertiserSurvey]] = ctx
        .get
        .flatMap:
          case Some(sessionData) =>
            sessionData.userId match
              case Some(userId) =>
                val videoIdStr = videoId.asString
                run(nextAdvertiserSurveyQuery(lift(videoIdStr), lift(userId)).take(1))
                  .flatMap(_.headOption.fold(ZIO.none)(fetchAdvertiserQuestionDetails))
                  .orDie
              case None =>
                ZIO.none
          case None =>
            ZIO.none

      private def fetchAdvertiserQuestionDetails(
          row: NextIdentificationSurveyRow): Task[Option[NextAdvertiserSurvey]] =
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
          yield NextAdvertiserSurvey(row.surveyId, advertiserIdOf(first.survey), question)

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
                firstEmailSurvey
          case None =>
            firstEmailSurvey

      /** The visitor has no user yet, so this is the only thing that can identify them.
        *
        * Finding nothing means the deployment has no active email survey, and the flow ends at
        * Ready rather than handing out access for nothing. That is a misconfiguration, not a
        * visitor state: log it, because from the outside it looks like the portal working.
        */
      private def firstEmailSurvey: Task[Option[NextIdentificationSurvey]] = run(
        firstEmailSurveyQuery.take(1))
        .orDie
        .flatMap: rows =>
          rows.headOption match
            case Some(row) =>
              fetchQuestionDetails(row).orDie
            case None =>
              ZIO
                .logWarning(
                  "No active email survey: an unidentified visitor cannot be identified, so the flow ends at Ready without granting access. Check list_surveys — the email survey or its questions may have been retired.")
                .as(None)

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
    // Fetch the question's texts, preferring the user's locale.
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
      case "dropdown" =>
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

  /** The rules of a question, read from the column the MCP backoffice writes.
    *
    * Through `surveySchemas`, which is what writes that column too. Before, this was a circe
    * decoder here and string interpolation there, agreeing by nobody's enforcement.
    *
    * A row that will not decode is dropped, and dropped silently — unchanged from the `.toOption`
    * this replaces, and worth stating plainly because it is not a safe default. A rule that fails
    * to decode is a validation that stops being applied, and a validation that is not applied looks
    * exactly like one that passed: no error, no log line, no record of which question lost which
    * rule. Fixing it means carrying the reason out to where an effect can log it, through
    * `parseQuestionType` and `buildQuestionToAnswer`, both of which are pure — a change worth
    * making on its own rather than inside this one.
    */
  private def decodeRules[R: Schema](rules: List[QuestionRuleRow]): List[R] = rules.flatMap(row =>
    surveySchemas.decode[R](row.ruleConfig).toOption)

  /** Who the video belonged to, when it belonged to anyone.
    *
    * Absent for a promo, and absent for every survey written through the MCP backoffice before it
    * started copying the video's advertiser onto the survey. Both read the same way here, which is
    * the point: the question is still asked, and the answer is simply attributed to nobody.
    *
    * This used to be bound inside the `Option` comprehensions above, so an absent advertiser threw
    * away the question along with it.
    */
  private def advertiserIdOf(row: SurveyRow): Option[survey.AdvertiserId] = row
    .advertiserId
    .flatMap(survey.AdvertiserId.fromString)

  private def parseHierarchyLevel(level: String): Option[HierarchyLevel] =
    decode[HierarchyLevel](s"\"$level\"").toOption

  private def toNextIdentificationSurvey(
      row: NextIdentificationSurveyRow,
      question: QuestionToAnswer): NextIdentificationSurvey =
    val surveyType =
      row.category match
        case SurveyCategory.Email =>
          IdentificationSurveyType.Email
        case SurveyCategory.Profiling =>
          IdentificationSurveyType.Profiling
        case SurveyCategory.Location =>
          IdentificationSurveyType.Location
        // Only the two identification queries feed this, and both filter by category, so nothing
        // else can arrive. Named rather than left to a MatchError so the reason is in the message.
        case other =>
          throw new IllegalStateException(
            s"'$other' is not an identification category. Only the email, profiling and location surveys reach this conversion.")

    NextIdentificationSurvey(row.surveyId, surveyType, question)

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, SurveyRepository[Task]] = ZLayer
    .fromFunction(apply)
end SurveyRepositoryQuill
