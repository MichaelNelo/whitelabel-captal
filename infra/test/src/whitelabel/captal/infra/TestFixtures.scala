package whitelabel.captal.infra

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.core.given
import zio.*

object TestFixtures:
  private val schema =
    """
    |CREATE TABLE users (
    |    id TEXT PRIMARY KEY,
    |    email TEXT,
    |    locale TEXT NOT NULL,
    |    created_at TEXT NOT NULL,
    |    updated_at TEXT NOT NULL
    |);
    |
    |CREATE INDEX idx_users_email ON users(email);
    |
    |CREATE TABLE surveys (
    |    id TEXT PRIMARY KEY,
    |    category TEXT NOT NULL CHECK (category IN ('email', 'profiling', 'location', 'advertiser')),
    |    advertiser_id TEXT,
    |    is_active INTEGER NOT NULL,
    |    created_at TEXT NOT NULL
    |);
    |
    |CREATE INDEX idx_surveys_category ON surveys(category);
    |CREATE INDEX idx_surveys_advertiser ON surveys(advertiser_id);
    |
    |CREATE TABLE questions (
    |    id TEXT PRIMARY KEY,
    |    survey_id TEXT NOT NULL REFERENCES surveys(id),
    |    question_type TEXT NOT NULL CHECK (question_type IN ('radio', 'checkbox', 'select', 'input', 'rating', 'numeric', 'date')),
    |    points_awarded INTEGER NOT NULL,
    |    display_order INTEGER NOT NULL,
    |    hierarchy_level TEXT CHECK (hierarchy_level IS NULL OR hierarchy_level IN ('state', 'city', 'municipality', 'urbanization')),
    |    is_required INTEGER NOT NULL,
    |    created_at TEXT NOT NULL
    |);
    |
    |CREATE INDEX idx_questions_survey ON questions(survey_id);
    |
    |CREATE TABLE sessions (
    |    id TEXT PRIMARY KEY,
    |    user_id TEXT REFERENCES users(id),
    |    device_id TEXT NOT NULL,
    |    locale TEXT NOT NULL,
    |    phase TEXT NOT NULL,
    |    current_survey_id TEXT REFERENCES surveys(id),
    |    current_question_id TEXT REFERENCES questions(id),
    |    created_at TEXT NOT NULL
    |);
    |
    |CREATE INDEX idx_sessions_user_id ON sessions(user_id);
    |
    |CREATE TABLE question_options (
    |    id TEXT PRIMARY KEY,
    |    question_id TEXT NOT NULL REFERENCES questions(id),
    |    display_order INTEGER NOT NULL,
    |    parent_option_id TEXT REFERENCES question_options(id)
    |);
    |
    |CREATE INDEX idx_question_options_question ON question_options(question_id);
    |
    |CREATE TABLE question_rules (
    |    id TEXT PRIMARY KEY,
    |    question_id TEXT NOT NULL REFERENCES questions(id),
    |    rule_type TEXT NOT NULL,
    |    rule_config TEXT NOT NULL
    |);
    |
    |CREATE INDEX idx_question_rules_question ON question_rules(question_id);
    |
    |CREATE TABLE answers (
    |    id TEXT PRIMARY KEY,
    |    user_id TEXT NOT NULL REFERENCES users(id),
    |    session_id TEXT NOT NULL REFERENCES sessions(id),
    |    question_id TEXT NOT NULL REFERENCES questions(id),
    |    answer_value TEXT NOT NULL,
    |    answered_at TEXT NOT NULL,
    |    created_at TEXT NOT NULL
    |);
    |
    |CREATE INDEX idx_answers_user ON answers(user_id);
    |CREATE INDEX idx_answers_question ON answers(question_id);
    |CREATE UNIQUE INDEX idx_answers_user_question ON answers(user_id, question_id);
    |
    |CREATE TABLE user_survey_progress (
    |    id TEXT PRIMARY KEY,
    |    user_id TEXT NOT NULL REFERENCES users(id),
    |    survey_id TEXT NOT NULL REFERENCES surveys(id),
    |    current_question_id TEXT REFERENCES questions(id),
    |    completed_at TEXT,
    |    created_at TEXT NOT NULL,
    |    updated_at TEXT NOT NULL
    |);
    |
    |CREATE UNIQUE INDEX idx_user_survey_progress_unique ON user_survey_progress(user_id, survey_id);
    |
    |CREATE TABLE localized_texts (
    |    id TEXT PRIMARY KEY,
    |    entity_id TEXT NOT NULL,
    |    locale TEXT NOT NULL,
    |    value TEXT NOT NULL,
    |    created_at TEXT NOT NULL,
    |    updated_at TEXT NOT NULL
    |);
    |
    |CREATE UNIQUE INDEX idx_localized_texts_entity_locale ON localized_texts(entity_id, locale);
    |""".stripMargin

  def migrate: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    ZIO.attemptBlocking:
      val conn = quill.ds.getConnection
      try
        val stmt = conn.createStatement()
        schema.split(";").filter(_.trim.nonEmpty).foreach(s => stmt.execute(s.trim))
        stmt.close()
      finally
        conn.close()

  // ─────────────────────────────────────────────────────────────────────────────
  // Survey Fixtures
  // ─────────────────────────────────────────────────────────────────────────────

  final case class SurveyFixture(surveyId: survey.Id, questionId: survey.question.Id)

  def seedEmailSurvey: ZIO[QuillSqlite, Throwable, SurveyFixture] =
    for
      fixture <- seedSurvey("email", "input", "What is your email?")
      _       <- addQuestionRule(fixture.questionId, "text", """{"type":"email"}""")
    yield fixture

  def seedProfilingSurvey: ZIO[QuillSqlite, Throwable, SurveyFixture] = seedSurvey(
    "profiling",
    "radio",
    "What is your age range?")

  def seedLocationSurvey: ZIO[QuillSqlite, Throwable, SurveyFixture] = seedSurvey(
    "location",
    "select",
    "What is your state?",
    hierarchyLevel = Some("state"))

  def seedAllIdentificationSurveys: ZIO[QuillSqlite, Throwable, AllSurveysFixture] =
    for
      email     <- seedEmailSurvey
      profiling <- seedProfilingSurvey
      location  <- seedLocationSurvey
    yield AllSurveysFixture(email, profiling, location)

  final case class AllSurveysFixture(
      email: SurveyFixture,
      profiling: SurveyFixture,
      location: SurveyFixture)

  final case class MultiQuestionSurveyFixture(
      surveyId: survey.Id,
      questions: List[survey.question.Id])

  def seedMultiQuestionProfilingSurvey: ZIO[QuillSqlite, Throwable, MultiQuestionSurveyFixture] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      val surveyId = survey.Id.generate
      val now = java.time.Instant.now.toString
      val surveyRow = SurveyRow(
        id = surveyId,
        category = "profiling",
        advertiserId = None,
        isActive = 1,
        createdAt = now)
      val questionsWithText = List(
        ("What is your age range?", 1),
        ("What is your gender?", 2),
        ("What is your occupation?", 3)).map: (text, order) =>
        val questionId = survey.question.Id.generate
        val questionRow = QuestionRow(
          id = questionId,
          surveyId = surveyId,
          questionType = "radio",
          pointsAwarded = 10,
          displayOrder = order,
          hierarchyLevel = None,
          isRequired = 1,
          createdAt = now)
        val textRow = LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = questionId.asString,
          locale = "en",
          value = text,
          createdAt = now,
          updatedAt = now)
        (questionRow, textRow)
      for
        _ <- run(query[SurveyRow].insertValue(lift(surveyRow)))
        _ <- ZIO.foreach(questionsWithText): (qRow, tRow) =>
          run(query[QuestionRow].insertValue(lift(qRow))) *>
            run(query[LocalizedTextRow].insertValue(lift(tRow)))
      yield MultiQuestionSurveyFixture(surveyId, questionsWithText.map(_._1.id))

  private def seedSurvey(
      category: String,
      questionType: String,
      questionText: String,
      hierarchyLevel: Option[String] = None): ZIO[QuillSqlite, Throwable, SurveyFixture] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      val surveyId = survey.Id.generate
      val questionId = survey.question.Id.generate
      val now = java.time.Instant.now.toString
      val surveyRow = SurveyRow(
        id = surveyId,
        category = category,
        advertiserId = None,
        isActive = 1,
        createdAt = now)
      val questionRow = QuestionRow(
        id = questionId,
        surveyId = surveyId,
        questionType = questionType,
        pointsAwarded = 10,
        displayOrder = 1,
        hierarchyLevel = hierarchyLevel,
        isRequired = 1,
        createdAt = now)
      val textRow = LocalizedTextRow(
        id = UUID.randomUUID.toString,
        entityId = questionId.asString,
        locale = "en",
        value = questionText,
        createdAt = now,
        updatedAt = now)
      for
        _ <- run(query[SurveyRow].insertValue(lift(surveyRow)))
        _ <- run(query[QuestionRow].insertValue(lift(questionRow)))
        _ <- run(query[LocalizedTextRow].insertValue(lift(textRow)))
      yield SurveyFixture(surveyId, questionId)

  def addQuestionOptions(
      questionId: survey.question.Id,
      options: List[String]): ZIO[QuillSqlite, Throwable, List[survey.question.OptionId]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      val now = java.time.Instant.now.toString
      val optionsWithText = options
        .zipWithIndex
        .map: (text, idx) =>
          val optionId = survey.question.OptionId.generate
          val optionRow = QuestionOptionRow(
            id = optionId,
            questionId = questionId,
            displayOrder = idx + 1,
            parentOptionId = None)
          val textRow = LocalizedTextRow(
            id = UUID.randomUUID.toString,
            entityId = optionId.asString,
            locale = "en",
            value = text,
            createdAt = now,
            updatedAt = now)
          (optionRow, textRow)
      for _ <- ZIO.foreach(optionsWithText): (optRow, txtRow) =>
        run(query[QuestionOptionRow].insertValue(lift(optRow))) *>
          run(query[LocalizedTextRow].insertValue(lift(txtRow)))
      yield optionsWithText.map(_._1.id)

  def addQuestionRule(
      questionId: survey.question.Id,
      ruleType: String,
      ruleConfig: String): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      val ruleRow = QuestionRuleRow(
        id = UUID.randomUUID.toString,
        questionId = questionId,
        ruleType = ruleType,
        ruleConfig = ruleConfig)
      run(query[QuestionRuleRow].insertValue(lift(ruleRow))).unit

  // ─────────────────────────────────────────────────────────────────────────────
  // User and Progress Fixtures
  // ─────────────────────────────────────────────────────────────────────────────

  final case class UserFixture(userId: user.Id, email: user.Email)

  def createUser(email: String): ZIO[QuillSqlite, Throwable, UserFixture] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    val userId = user.Id.generate
    val userEmail = user.Email.unsafeFrom(email)
    val now = java.time.Instant.now.toString
    val userRow = UserRow(
      id = userId,
      email = Some(userEmail),
      locale = "en",
      createdAt = now,
      updatedAt = now)
    run(query[UserRow].insertValue(lift(userRow))).as(UserFixture(userId, userEmail))

  def markSurveyCompleted(userId: user.Id, surveyId: survey.Id): ZIO[QuillSqlite, Throwable, Unit] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      val now = java.time.Instant.now.toString
      val progressRow = UserSurveyProgressRow(
        id = UUID.randomUUID.toString,
        userId = userId,
        surveyId = surveyId,
        currentQuestionId = None,
        completedAt = Some(now),
        createdAt = now,
        updatedAt = now)
      run(query[UserSurveyProgressRow].insertValue(lift(progressRow))).unit

  def createAnswer(
      userId: user.Id,
      sessionId: user.SessionId,
      questionId: survey.question.Id,
      answerValue: String): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      val now = java.time.Instant.now.toString
      val answerRow = AnswerRow(
        id = UUID.randomUUID.toString,
        userId = userId,
        sessionId = sessionId,
        questionId = questionId,
        answerValue = answerValue,
        answeredAt = now,
        createdAt = now)
      run(query[AnswerRow].insertValue(lift(answerRow))).unit

  def linkSessionToUser(
      sessionId: user.SessionId,
      userId: user.Id): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(query[SessionRow].filter(_.id == lift(sessionId)).update(_.userId -> Some(lift(userId))))
        .unit

  def updateSessionPhase(
      sessionId: user.SessionId,
      phase: Phase): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    run(query[SessionRow].filter(_.id == lift(sessionId)).update(_.phase -> lift(phase))).unit

  // ─────────────────────────────────────────────────────────────────────────────
  // Query Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  final case class DbState(
      users: List[UserRow],
      answers: List[AnswerRow],
      sessions: List[SessionRow],
      progress: List[UserSurveyProgressRow])

  def queryDbState: ZIO[QuillSqlite, Throwable, DbState] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    for
      users    <- run(query[UserRow])
      answers  <- run(query[AnswerRow])
      sessions <- run(query[SessionRow])
      progress <- run(query[UserSurveyProgressRow])
    yield DbState(users, answers, sessions, progress)

  def getSession(sessionId: user.SessionId): ZIO[QuillSqlite, Throwable, Option[SessionRow]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[SessionRow].filter(_.id == lift(sessionId))).map(_.headOption)

  def countUsersByEmail(email: user.Email): ZIO[QuillSqlite, Throwable, Long] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(query[UserRow].filter(_.email.contains(lift(email))).size)

  def getUserByEmail(email: user.Email): ZIO[QuillSqlite, Throwable, Option[UserRow]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[UserRow].filter(_.email.contains(lift(email)))).map(_.headOption)

  def clearAllData: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    ZIO.attemptBlocking:
      val conn = quill.ds.getConnection
      try
        val stmt = conn.createStatement()
        stmt.execute("DELETE FROM user_survey_progress")
        stmt.execute("DELETE FROM answers")
        stmt.execute("DELETE FROM sessions")
        stmt.execute("DELETE FROM localized_texts")
        stmt.execute("DELETE FROM question_rules")
        stmt.execute("DELETE FROM question_options")
        stmt.execute("DELETE FROM questions")
        stmt.execute("DELETE FROM surveys")
        stmt.execute("DELETE FROM users")
        stmt.close()
      finally
        conn.close()

  // ─────────────────────────────────────────────────────────────────────────────
  // Locale Fixtures
  // ─────────────────────────────────────────────────────────────────────────────

  def seedLocales(locales: List[String]): ZIO[QuillSqlite, Throwable, Unit] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      val now = java.time.Instant.now.toString
      val rows = locales.map: locale =>
        LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = s"ui.locale.$locale",
          locale = locale,
          value = locale,
          createdAt = now,
          updatedAt = now)
      ZIO.foreach(rows)(row => run(query[LocalizedTextRow].insertValue(lift(row)))).unit

  def countSessions: ZIO[QuillSqlite, Throwable, Long] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[SessionRow].size)

  // ─────────────────────────────────────────────────────────────────────────────
  // Noise Data - Unrelated records to test query filtering
  // ─────────────────────────────────────────────────────────────────────────────

  def seedNoiseData: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    val now = java.time.Instant.now.toString

    // Create multiple unrelated users
    val noiseUsers = (1 to 5).map: i =>
      UserRow(
        id = user.Id.generate,
        email = Some(user.Email.unsafeFrom(s"noise-user-$i@example.com")),
        locale = "es",
        createdAt = now,
        updatedAt = now)

    // Create inactive surveys (should be ignored by queries)
    val inactiveSurveys = List("email", "profiling", "location").map: category =>
      val surveyId = survey.Id.generate
      val questionId = survey.question.Id.generate
      val surveyRow = SurveyRow(
        id = surveyId,
        category = category,
        advertiserId = None,
        isActive = 0, // Inactive!
        createdAt = now)
      val questionRow = QuestionRow(
        id = questionId,
        surveyId = surveyId,
        questionType = "input",
        pointsAwarded = 5,
        displayOrder = 1,
        hierarchyLevel = None,
        isRequired = 1,
        createdAt = now)
      val textRow = LocalizedTextRow(
        id = UUID.randomUUID.toString,
        entityId = questionId.asString,
        locale = "en",
        value = s"Inactive $category question",
        createdAt = now,
        updatedAt = now)
      (surveyRow, questionRow, textRow)

    // Create extra active surveys with different questions
    val extraSurveys = List(
      ("email", "What is your work email?"),
      ("profiling", "What is your income range?"),
      ("location", "What is your city?")).map: (category, text) =>
      val surveyId = survey.Id.generate
      val surveyRow = SurveyRow(
        id = surveyId,
        category = category,
        advertiserId = None,
        isActive = 1,
        createdAt = now)
      val questionsWithText = (1 to 3).map: order =>
        val questionId = survey.question.Id.generate
        val questionRow = QuestionRow(
          id = questionId,
          surveyId = surveyId,
          questionType = if category == "email" then "input" else "radio",
          pointsAwarded = 10,
          displayOrder = order,
          hierarchyLevel = if category == "location" then Some("city") else None,
          isRequired = 1,
          createdAt = now)
        val textRow = LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = questionId.asString,
          locale = "en",
          value = s"$text (Q$order)",
          createdAt = now,
          updatedAt = now)
        (questionRow, textRow)
      (surveyRow, questionsWithText.toList)

    // Create completed progress for noise users on some surveys
    val noiseProgress = noiseUsers.take(3).flatMap: noiseUser =>
      extraSurveys.map: (surveyRow, _) =>
        UserSurveyProgressRow(
          id = UUID.randomUUID.toString,
          userId = noiseUser.id,
          surveyId = surveyRow.id,
          currentQuestionId = None,
          completedAt = Some(now),
          createdAt = now,
          updatedAt = now)

    for
      // Insert noise users
      _ <- ZIO.foreach(noiseUsers)(row => run(query[UserRow].insertValue(lift(row))))
      // Insert inactive surveys with their questions and texts
      _ <- ZIO.foreach(inactiveSurveys): (surveyRow, questionRow, textRow) =>
        run(query[SurveyRow].insertValue(lift(surveyRow))) *>
          run(query[QuestionRow].insertValue(lift(questionRow))) *>
          run(query[LocalizedTextRow].insertValue(lift(textRow)))
      // Insert extra active surveys with multiple questions
      _ <- ZIO.foreach(extraSurveys): (surveyRow, questionsWithText) =>
        run(query[SurveyRow].insertValue(lift(surveyRow))) *>
          ZIO.foreach(questionsWithText): (qRow, tRow) =>
            run(query[QuestionRow].insertValue(lift(qRow))) *>
              run(query[LocalizedTextRow].insertValue(lift(tRow)))
      // Insert noise progress
      _ <- ZIO.foreach(noiseProgress)(row =>
        run(query[UserSurveyProgressRow].insertValue(lift(row))))
    yield ()
end TestFixtures
