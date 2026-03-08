package whitelabel.captal.api

import java.util.UUID

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.{survey, user, video}
import whitelabel.captal.infra.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import zio.*
import zio.interop.catz.*

object TestFixtures:
  private val testConfig = ConfigFactory.load("test.conf")
  private val fly4sConfig = Fly4sConfig.default.copy(
    locations = List(Location("db/migration")),
    baselineOnMigrate = true,
    baselineVersion = MigrationVersion("0"),
    cleanOnValidationError = true)

  def migrate: ZIO[Any, Throwable, Unit] = ZIO
    .attempt:
      val url = testConfig.getString("database.jdbcUrl")
      RqliteDataSource.create(url)
    .flatMap: ds =>
      Fly4s.makeFor[Task](ZIO.succeed(ds), config = fly4sConfig).use(_.migrate).unit

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
    "dropdown",
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
          category = "backend",
          createdAt = now,
          updatedAt = now)
        (questionRow, textRow)
      for
        _ <- run(query[SurveyRow].insertValue(lift(surveyRow)))
        _ <-
          ZIO.foreach(questionsWithText): (qRow, tRow) =>
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
        category = "backend",
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
            category = "backend",
            createdAt = now,
            updatedAt = now)
          (optionRow, textRow)
      for _ <-
          ZIO.foreach(optionsWithText): (optRow, txtRow) =>
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
        stmt.execute("DELETE FROM video_views")
        stmt.execute("DELETE FROM user_survey_progress")
        stmt.execute("DELETE FROM answers")
        stmt.execute("DELETE FROM sessions")
        stmt.execute("DELETE FROM localized_texts")
        stmt.execute("DELETE FROM question_rules")
        stmt.execute("DELETE FROM question_options")
        stmt.execute("DELETE FROM questions")
        stmt.execute("DELETE FROM surveys")
        stmt.execute("DELETE FROM advertiser_videos")
        stmt.execute("DELETE FROM advertisers")
        stmt.execute("DELETE FROM users")
        stmt.close()
      finally
        conn.close()

  // ─────────────────────────────────────────────────────────────────────────────
  // Locale Fixtures
  // ─────────────────────────────────────────────────────────────────────────────

  def seedLocales(locales: List[String]): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    val now = java.time.Instant.now.toString
    val rows = locales.map: locale =>
      LocalizedTextRow(
        id = UUID.randomUUID.toString,
        entityId = s"ui.locale.$locale",
        locale = locale,
        value = locale,
        category = "frontend",
        createdAt = now,
        updatedAt = now)
    ZIO.foreach(rows)(row => run(query[LocalizedTextRow].insertValue(lift(row)))).unit

  def countSessions: ZIO[QuillSqlite, Throwable, Long] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
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
        category = "backend",
        createdAt = now,
        updatedAt = now
      )
      (surveyRow, questionRow, textRow)

    // Create extra active advertiser surveys (noise that shouldn't interfere with identification flow)
    val extraSurveys = List(
      ("advertiser", "Advertiser 1", "adv-001"),
      ("advertiser", "Advertiser 2", "adv-002"),
      ("advertiser", "Advertiser 3", "adv-003")).map: (category, text, advId) =>
      val surveyId = survey.Id.generate
      val surveyRow = SurveyRow(
        id = surveyId,
        category = category,
        advertiserId = Some(advId),
        isActive = 1,
        createdAt = now)
      val questionsWithText = (1 to 3).map: order =>
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
          value = s"$text Question $order",
          category = "backend",
          createdAt = now,
          updatedAt = now)
        (questionRow, textRow)
      (surveyRow, questionsWithText.toList)

    // Create completed progress for noise users on some surveys
    val noiseProgress = noiseUsers
      .take(3)
      .flatMap: noiseUser =>
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
      _ <-
        ZIO.foreach(inactiveSurveys): (surveyRow, questionRow, textRow) =>
          run(query[SurveyRow].insertValue(lift(surveyRow))) *>
            run(query[QuestionRow].insertValue(lift(questionRow))) *>
            run(query[LocalizedTextRow].insertValue(lift(textRow)))
      // Insert extra active surveys with multiple questions
      _ <-
        ZIO.foreach(extraSurveys): (surveyRow, questionsWithText) =>
          run(query[SurveyRow].insertValue(lift(surveyRow))) *>
            ZIO.foreach(questionsWithText): (qRow, tRow) =>
              run(query[QuestionRow].insertValue(lift(qRow))) *>
                run(query[LocalizedTextRow].insertValue(lift(tRow)))
      // Insert noise progress
      _ <-
        ZIO.foreach(noiseProgress)(row => run(query[UserSurveyProgressRow].insertValue(lift(row))))
    yield ()
    end for

  // ─────────────────────────────────────────────────────────────────────────────
  // Video Fixtures
  // ─────────────────────────────────────────────────────────────────────────────

  final case class VideoFixture(
      advertiserId: String,
      videoId: video.Id,
      videoUrl: String,
      durationSeconds: Int,
      title: Option[String] = None)

  def seedAdvertiserWithVideo(
      advertiserName: String = "Test Advertiser",
      videoUrl: String = "https://cdn.example.com/video.mp4",
      videoTitle: String = "Test Video",
      durationSeconds: Int = 15): ZIO[QuillSqlite, Throwable, VideoFixture] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    val now = java.time.Instant.now.toString
    val advertiserId = UUID.randomUUID.toString
    val videoId = video.Id.generate

    val advertiserRow = AdvertiserRow(
      id = advertiserId,
      name = advertiserName,
      priority = 10,
      isActive = 1,
      createdAt = now,
      updatedAt = now)

    val videoRow = AdvertiserVideoRow(
      id = videoId,
      advertiserId = Some(advertiserId),
      videoType = "publicidad",
      videoUrl = videoUrl,
      durationSeconds = durationSeconds,
      minWatchSeconds = 8,
      showCountdown = 1,
      noRepeatSeconds = None,
      isActive = 1,
      priority = 10,
      createdAt = now,
      updatedAt = now)

    // Localized text for video title
    val titleRow = LocalizedTextRow(
      id = UUID.randomUUID.toString,
      entityId = videoId.asString,
      locale = "en",
      value = videoTitle,
      category = "backend",
      createdAt = now,
      updatedAt = now)

    for
      _ <- run(query[AdvertiserRow].insertValue(lift(advertiserRow)))
      _ <- run(query[AdvertiserVideoRow].insertValue(lift(videoRow)))
      _ <- run(query[LocalizedTextRow].insertValue(lift(titleRow)))
    yield VideoFixture(advertiserId, videoId, videoUrl, durationSeconds, Some(videoTitle))

  def seedPromoVideo(
      videoUrl: String = "https://cdn.example.com/promo.mp4",
      videoTitle: String = "Promo Video",
      durationSeconds: Int = 10,
      priority: Int = 1): ZIO[QuillSqlite, Throwable, VideoFixture] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      val now = java.time.Instant.now.toString
      val videoId = video.Id.generate

      val videoRow = AdvertiserVideoRow(
        id = videoId,
        advertiserId = None,
        videoType = "propaganda",
        videoUrl = videoUrl,
        durationSeconds = durationSeconds,
        minWatchSeconds = 5,
        showCountdown = 0,
        noRepeatSeconds = None,
        isActive = 1,
        priority = priority,
        createdAt = now,
        updatedAt = now)

      // Localized text for video title
      val titleRow = LocalizedTextRow(
        id = UUID.randomUUID.toString,
        entityId = videoId.asString,
        locale = "en",
        value = videoTitle,
        category = "backend",
        createdAt = now,
        updatedAt = now)

      for
        _ <- run(query[AdvertiserVideoRow].insertValue(lift(videoRow)))
        _ <- run(query[LocalizedTextRow].insertValue(lift(titleRow)))
      yield VideoFixture("", videoId, videoUrl, durationSeconds, Some(videoTitle))

  def getVideoViews: ZIO[QuillSqlite, Throwable, List[VideoViewRow]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(query[VideoViewRow])

  def updateSessionCurrentVideo(
      sessionId: user.SessionId,
      videoId: video.Id): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    run(
      query[SessionRow]
        .filter(_.id == lift(sessionId))
        .update(_.currentVideoId -> Some(lift(videoId)))).unit

  def clearVideoData: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    ZIO.attemptBlocking:
      val conn = quill.ds.getConnection
      try
        val stmt = conn.createStatement()
        stmt.execute("DELETE FROM video_views")
        stmt.execute("DELETE FROM advertiser_videos")
        stmt.execute("DELETE FROM advertisers")
        stmt.close()
      finally
        conn.close()
end TestFixtures
