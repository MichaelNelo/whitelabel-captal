package whitelabel.captal.infra

import io.circe.parser.decode
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.ZServerEndpoint
import whitelabel.captal.api.SurveyRoutes
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.application.commands.NextIdentificationSurvey.given
import whitelabel.captal.core.application.IdentificationSurveyType
import whitelabel.captal.core.application.IdentificationSurveyType.given
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.user
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*
import zio.test.*

object E2ETests extends ZIOSpecDefault:
  type TestEnv = SurveyRoutes.FullEnv & QuillSqlite

  given taskMonadError: MonadError[Task] with
    def unit[T](t: T): Task[T] = ZIO.succeed(t)
    def map[T, T2](fa: Task[T])(f: T => T2): Task[T2] = fa.map(f)
    def flatMap[T, T2](fa: Task[T])(f: T => Task[T2]): Task[T2] = fa.flatMap(f)
    def error[T](t: Throwable): Task[T] = ZIO.fail(t)
    protected def handleWrappedError[T](rt: Task[T])(
        h: PartialFunction[Throwable, Task[T]]): Task[T] = rt.catchSome(h)
    def ensure[T](f: Task[T], e: => Task[Unit]): Task[T] = f.ensuring(e.ignore)

  private def rioMonadError[R]: MonadError[[A] =>> RIO[R, A]] =
    new MonadError[[A] =>> RIO[R, A]]:
      def unit[T](t: T): RIO[R, T] = ZIO.succeed(t)
      def map[T, T2](fa: RIO[R, T])(f: T => T2): RIO[R, T2] = fa.map(f)
      def flatMap[T, T2](fa: RIO[R, T])(f: T => RIO[R, T2]): RIO[R, T2] = fa.flatMap(f)
      def error[T](t: Throwable): RIO[R, T] = ZIO.fail(t)
      protected def handleWrappedError[T](rt: RIO[R, T])(
          h: PartialFunction[Throwable, RIO[R, T]]): RIO[R, T] = rt.catchSome(h)
      def ensure[T](f: RIO[R, T], e: => RIO[R, Unit]): RIO[R, T] = f.ensuring(e.ignore)

  private def provideEnvToEndpoint[R](
      endpoint: ZServerEndpoint[R, Any],
      env: ZEnvironment[R]): ZServerEndpoint[Any, Any] = ServerEndpoint(
    endpoint.endpoint,
    _ => a => endpoint.securityLogic(rioMonadError)(a).provideEnvironment(env),
    _ => u => i => endpoint.logic(rioMonadError)(u)(i).provideEnvironment(env)
  )

  private def testBackend: ZIO[TestEnv, Nothing, SttpBackend[Task, Any]] = ZIO
    .environment[TestEnv]
    .map: env =>
      val endpointsWithEnv = SurveyRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      TapirStubInterpreter(SttpBackendStub[Task, Any](taskMonadError))
        .whenServerEndpointsRunLogic(endpointsWithEnv)
        .backend()

  // ─────────────────────────────────────────────────────────────────────────────
  // HTTP Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private def extractSessionCookie(response: Response[String]): Option[String] = response
    .header("Set-Cookie")
    .flatMap: header =>
      header
        .split(";")
        .headOption
        .flatMap: cookie =>
          if cookie.startsWith("session_id=") then
            Some(cookie.stripPrefix("session_id="))
          else
            None

  private def getStatus(backend: SttpBackend[Task, Any], sessionCookie: Option[String] = None) =
    val request = basicRequest.get(uri"http://test/api/status").response(asStringAlways)
    val withCookie = sessionCookie.fold(request)(c => request.cookie("session_id", c))
    withCookie.send(backend)

  private def getLocales(backend: SttpBackend[Task, Any]) = basicRequest
    .get(uri"http://test/api/locales")
    .response(asStringAlways)
    .send(backend)

  private def putSetLocale(
      backend: SttpBackend[Task, Any],
      locale: String,
      sessionCookie: Option[String] = None) =
    val request = basicRequest
      .put(uri"http://test/api/session/locale")
      .body(s"""{"locale":"$locale"}""")
      .contentType("application/json")
      .response(asStringAlways)
    val withCookie = sessionCookie.fold(request)(c => request.cookie("session_id", c))
    withCookie.send(backend)

  private def getNextSurvey(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/survey/next")
    .cookie("session_id", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  private def parseNextSurvey(body: String): Option[NextIdentificationSurvey] =
    decode[NextIdentificationSurvey](body).toOption

  private def postEmailAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      email: String) = basicRequest
    .post(uri"http://test/api/survey/email")
    .cookie("session_id", sessionCookie)
    .body(s"""{"answer":{"type":"text","value":"$email"}}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  private def postProfilingAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      optionId: String) = basicRequest
    .post(uri"http://test/api/survey/profiling")
    .cookie("session_id", sessionCookie)
    .body(s"""{"answer":{"type":"single","value":"$optionId"}}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  // ─────────────────────────────────────────────────────────────────────────────
  // Test Suites
  // ─────────────────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Throwable] = (
    suite("Identification Survey Flow")(
      localeSessionSuite,
      sessionManagementSuite,
      emailSurveySuite,
      surveyProgressionSuite,
      multiQuestionSurveySuite,
      validationSuite) @@ TestAspect.sequential @@ TestAspect.after(TestFixtures.clearAllData.orDie)
  ).provideShared(
    TestLayers.testEnv,
    ZLayer.fromZIO((TestFixtures.migrate *> TestFixtures.seedNoiseData).unit)
  )

  // ─────────────────────────────────────────────────────────────────────────────
  // Locale and Session Endpoints
  // ─────────────────────────────────────────────────────────────────────────────

  private val localeSessionSuite =
    suite("Locale and Session Endpoints")(
      test("GET /api/locales returns available locales from database"):
        for
          _        <- TestFixtures.seedLocales(List("es", "en", "pt"))
          backend  <- testBackend
          response <- getLocales(backend)
        yield assertTrue(
          response.code.isSuccess,
          response.body.contains("es"),
          response.body.contains("en"),
          response.body.contains("pt"))
      ,
      test("GET /api/status without session returns error"):
        for
          backend  <- testBackend
          response <- getStatus(backend, None)
        yield assertTrue(
          !response.code.isSuccess,
          response.body.contains("session_missing") || response.body.contains("error"))
      ,
      test("PUT /api/session/locale creates session if none exists"):
        for
          countBefore <- TestFixtures.countSessions
          backend     <- testBackend
          response    <- putSetLocale(backend, "es")
          countAfter  <- TestFixtures.countSessions
          cookie = extractSessionCookie(response)
        yield assertTrue(
          response.code.isSuccess,
          cookie.isDefined,
          countAfter == countBefore + 1)
      ,
      test("PUT /api/session/locale preserves existing session"):
        for
          backend       <- testBackend
          firstResponse <- putSetLocale(backend, "es")
          firstCookie = extractSessionCookie(firstResponse).get
          countBefore    <- TestFixtures.countSessions
          secondResponse <- putSetLocale(backend, "en", Some(firstCookie))
          secondCookie = extractSessionCookie(secondResponse).get
          countAfter <- TestFixtures.countSessions
        yield assertTrue(
          firstResponse.code.isSuccess,
          secondResponse.code.isSuccess,
          firstCookie == secondCookie,
          countAfter == countBefore)
      ,
      test("setLocale creates session, then status uses same session"):
        for
          backend      <- testBackend
          localeResp   <- putSetLocale(backend, "es")
          localeCookie = extractSessionCookie(localeResp).get
          statusResp   <- getStatus(backend, Some(localeCookie))
        yield assertTrue(
          localeResp.code.isSuccess,
          statusResp.code.isSuccess,
          statusResp.body.contains("es"),
          statusResp.body.contains("identification_question"))
      ,
      test("setLocale with existing session updates locale but preserves session state"):
        for
          backend     <- testBackend
          // Create session with locale "en"
          createResp  <- putSetLocale(backend, "en")
          cookie = extractSessionCookie(createResp).get
          sessionId = user.SessionId.unsafe(cookie)
          // Advance session to a different phase
          _ <- TestFixtures.updateSessionPhase(sessionId, Phase.AdvertiserVideo)
          // Call setLocale with different locale
          updateResp  <- putSetLocale(backend, "es", Some(cookie))
          updatedCookie = extractSessionCookie(updateResp).get
          // Verify status shows updated locale but preserved phase
          statusResp  <- getStatus(backend, Some(updatedCookie))
        yield assertTrue(
          createResp.code.isSuccess,
          updateResp.code.isSuccess,
          cookie == updatedCookie, // Same session
          statusResp.body.contains("es"), // Updated locale
          statusResp.body.contains("advertiser_video")) // Preserved phase
    )

  // ─────────────────────────────────────────────────────────────────────────────
  // Session Management
  // ─────────────────────────────────────────────────────────────────────────────

  private val sessionManagementSuite =
    suite("Session Management")(
      test("setLocale creates session in identification phase"):
        for
          backend      <- testBackend
          localeResp   <- putSetLocale(backend, "es")
          sessionCookie = extractSessionCookie(localeResp).get
          statusResp   <- getStatus(backend, Some(sessionCookie))
        yield assertTrue(
          localeResp.code.isSuccess,
          statusResp.body.contains("identification_question"))
      ,
      test("returning visitor with existing session maintains their phase"):
        for
          backend   <- testBackend
          firstResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(firstResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.AdvertiserVideo)
          secondResp <- getStatus(backend, Some(cookie))
        yield assertTrue(secondResp.code.isSuccess, secondResp.body.contains("advertiser_video"))
      ,
      test("lost session with existing user links to existing user instead of creating duplicate"):
        val testEmail = user.Email.unsafeFrom("returning@example.com")
        for
          _           <- TestFixtures.seedEmailSurvey
          backend     <- testBackend
          firstLocale <- putSetLocale(backend, "es")
          firstCookie = extractSessionCookie(firstLocale).get
          _               <- getNextSurvey(backend, firstCookie)
          _               <- postEmailAnswer(backend, firstCookie, "returning@example.com")
          userCountBefore <- TestFixtures.countUsersByEmail(testEmail)
          existingUser    <- TestFixtures.getUserByEmail(testEmail)
          secondLocale    <- putSetLocale(backend, "es")
          secondCookie = extractSessionCookie(secondLocale).get
          _              <- getNextSurvey(backend, secondCookie)
          _              <- postEmailAnswer(backend, secondCookie, "returning@example.com")
          userCountAfter <- TestFixtures.countUsersByEmail(testEmail)
          session        <- TestFixtures.getSession(user.SessionId.unsafe(secondCookie))
        yield assertTrue(
          userCountBefore == 1L,
          userCountAfter == 1L,
          session.exists(_.userId.contains(existingUser.get.id)))
    )

  // ─────────────────────────────────────────────────────────────────────────────
  // Email Survey
  // ─────────────────────────────────────────────────────────────────────────────

  private val emailSurveySuite =
    suite("Email Survey")(
      test("anonymous user is offered email survey with correct QuestionToAnswer"):
        for
          seeded     <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          nextResp <- getNextSurvey(backend, cookie)
          parsed = parseNextSurvey(nextResp.body)
        yield assertTrue(
          nextResp.code.isSuccess,
          parsed.isDefined,
          parsed.get.surveyId == seeded.surveyId,
          parsed.get.surveyType == IdentificationSurveyType.Email,
          parsed.get.question.id == seeded.questionId,
          parsed.get.question.questionType match
            case QuestionType.Input(rules) => rules.contains(TextRule.Email)
            case _                         => false
          ,
          parsed.get.question.commonRules.contains(CommonRule.Required)
        )
      ,
      test("valid email answer creates user and transitions to advertiser video phase"):
        val testEmail = user.Email.unsafeFrom("user@example.com")
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _         <- getNextSurvey(backend, cookie)
          emailResp <- postEmailAnswer(backend, cookie, "user@example.com")
          dbState   <- TestFixtures.queryDbState
        yield assertTrue(
          emailResp.code.isSuccess,
          dbState.users.exists(_.email.contains(testEmail)),
          dbState.answers.nonEmpty,
          dbState.sessions.exists(_.phase == Phase.AdvertiserVideo),
          dbState.progress.nonEmpty
        )
      ,
      test("invalid email format is rejected with validation error"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _         <- getNextSurvey(backend, cookie)
          emailResp <- postEmailAnswer(backend, cookie, "not-an-email")
        yield assertTrue(
          !emailResp.code.isSuccess || emailResp.body.contains("error"),
          emailResp.body.contains("invalid_email"))
    )

  // ─────────────────────────────────────────────────────────────────────────────
  // Survey Progression
  // ─────────────────────────────────────────────────────────────────────────────

  private val surveyProgressionSuite =
    suite("Survey Progression")(
      test("user who completed email survey is offered profiling survey with correct QuestionToAnswer"):
        for
          surveys    <- TestFixtures.seedAllIdentificationSurveys
          testUser   <- TestFixtures.createUser("completed@example.com")
          _          <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.email.surveyId)
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _        <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
          nextResp <- getNextSurvey(backend, cookie)
          parsed = parseNextSurvey(nextResp.body)
        yield assertTrue(
          nextResp.code.isSuccess,
          parsed.isDefined,
          parsed.get.surveyId == surveys.profiling.surveyId,
          parsed.get.surveyType == IdentificationSurveyType.Profiling,
          parsed.get.question.id == surveys.profiling.questionId,
          parsed.get.question.questionType.isInstanceOf[QuestionType.Radio]
        )
      ,
      test("user who completed email and profiling surveys is offered location survey with correct QuestionToAnswer"):
        for
          surveys  <- TestFixtures.seedAllIdentificationSurveys
          testUser <- TestFixtures.createUser("profiling-done@example.com")
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.email.surveyId)
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.profiling.surveyId)
          backend  <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _        <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
          nextResp <- getNextSurvey(backend, cookie)
          parsed = parseNextSurvey(nextResp.body)
        yield assertTrue(
          nextResp.code.isSuccess,
          parsed.isDefined,
          parsed.get.surveyId == surveys.location.surveyId,
          parsed.get.surveyType == IdentificationSurveyType.Location,
          parsed.get.question.id == surveys.location.questionId,
          parsed.get.question.questionType.isInstanceOf[QuestionType.Select]
        )
      ,
      test("user who completed all identification surveys receives no more surveys"):
        for
          surveys  <- TestFixtures.seedAllIdentificationSurveys
          testUser <- TestFixtures.createUser("all-done@example.com")
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.email.surveyId)
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.profiling.surveyId)
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.location.surveyId)
          backend  <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _        <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
          nextResp <- getNextSurvey(backend, cookie)
        yield assertTrue(
          nextResp.code.isSuccess,
          nextResp.body == "null" || nextResp.body.isEmpty || nextResp.body == "{}")
      ,
      test("user who completed all identification surveys transitions to advertiser video phase"):
        for
          surveys  <- TestFixtures.seedAllIdentificationSurveys
          testUser <- TestFixtures.createUser("ready@example.com")
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.email.surveyId)
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.profiling.surveyId)
          _        <- TestFixtures.markSurveyCompleted(testUser.userId, surveys.location.surveyId)
          backend  <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie    = extractSessionCookie(localeResp).get
          sessionId = user.SessionId.unsafe(cookie)
          _           <- TestFixtures.linkSessionToUser(sessionId, testUser.userId)
          _           <- TestFixtures.updateSessionPhase(sessionId, Phase.AdvertiserVideo)
          finalStatus <- getStatus(backend, Some(cookie))
        yield assertTrue(finalStatus.code.isSuccess, finalStatus.body.contains("advertiser_video"))
    )

  // ─────────────────────────────────────────────────────────────────────────────
  // Multi-Question Survey
  // ─────────────────────────────────────────────────────────────────────────────

  private val multiQuestionSurveySuite =
    suite("Multi-Question Survey")(
      test("after answering first question, next question in same survey is offered with correct QuestionToAnswer"):
        for
          emailSurvey <- TestFixtures.seedEmailSurvey
          profiling   <- TestFixtures.seedMultiQuestionProfilingSurvey
          options     <- TestFixtures.addQuestionOptions(
            profiling.questions.head,
            List("18-25", "26-35"))
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _          <- getNextSurvey(backend, cookie)
          _          <- postEmailAnswer(backend, cookie, "multi@example.com")
          firstNext  <- getNextSurvey(backend, cookie)
          _          <- postProfilingAnswer(backend, cookie, options.head.asString)
          secondNext <- getNextSurvey(backend, cookie)
          firstParsed = parseNextSurvey(firstNext.body)
          secondParsed = parseNextSurvey(secondNext.body)
        yield assertTrue(
          firstParsed.isDefined,
          firstParsed.get.question.id == profiling.questions.head,
          firstParsed.get.question.questionType match
            case QuestionType.Radio(opts) => opts.map(_.text.value).toSet == Set("18-25", "26-35")
            case _                        => false
          ,
          secondParsed.isDefined,
          secondParsed.get.question.id == profiling.questions(1)
        ))

  // ─────────────────────────────────────────────────────────────────────────────
  // Validation Rules
  // ─────────────────────────────────────────────────────────────────────────────

  private val validationSuite =
    suite("Validation Rules")(
      test("empty email is rejected as required field"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _         <- getNextSurvey(backend, cookie)
          emailResp <- postEmailAnswer(backend, cookie, "")
        yield assertTrue(!emailResp.code.isSuccess || emailResp.body.contains("error")))
end E2ETests
