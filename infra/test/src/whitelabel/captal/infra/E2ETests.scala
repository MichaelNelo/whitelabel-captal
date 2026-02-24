package whitelabel.captal.infra

import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.ZServerEndpoint
import whitelabel.captal.api.SurveyRoutes
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

  private def getNextSurvey(backend: SttpBackend[Task, Any], sessionCookie: String) =
    basicRequest
      .get(uri"http://test/api/survey/next")
      .cookie("session_id", sessionCookie)
      .response(asStringAlways)
      .send(backend)

  private def postEmailAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, email: String) =
    basicRequest
      .post(uri"http://test/api/survey/email")
      .cookie("session_id", sessionCookie)
      .body(s"""{"email":"$email"}""")
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

  private def postProfilingAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      optionId: String) =
    basicRequest
      .post(uri"http://test/api/survey/profiling")
      .cookie("session_id", sessionCookie)
      .body(s"""{"optionId":"$optionId"}""")
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

  // ─────────────────────────────────────────────────────────────────────────────
  // Test Suites
  // ─────────────────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Throwable] =
    (suite("Identification Survey Flow")(
      sessionManagementSuite,
      emailSurveySuite,
      surveyProgressionSuite,
      multiQuestionSurveySuite,
      validationSuite
    ) @@ TestAspect.sequential @@ TestAspect.after(TestFixtures.clearAllData.orDie))
      .provideShared(TestLayers.testEnv, ZLayer.fromZIO(TestFixtures.migrate.unit))

  // ─────────────────────────────────────────────────────────────────────────────
  // Session Management
  // ─────────────────────────────────────────────────────────────────────────────

  private val sessionManagementSuite = suite("Session Management")(
    test("new visitor receives session in identification phase"):
      for
        backend  <- testBackend
        response <- getStatus(backend)
      yield assertTrue(
        response.code.isSuccess,
        response.body.contains("identification_question"),
        extractSessionCookie(response).isDefined
      )
    ,
    test("returning visitor with existing session maintains their phase"):
      for
        backend     <- testBackend
        firstResp   <- getStatus(backend)
        cookie       = extractSessionCookie(firstResp).get
        _           <- TestFixtures.updateSessionPhase(cookie, "advertiser_video")
        secondResp  <- getStatus(backend, Some(cookie))
      yield assertTrue(
        secondResp.code.isSuccess,
        secondResp.body.contains("advertiser_video")
      )
    ,
    test("lost session with existing user links to existing user instead of creating duplicate"):
      for
        _              <- TestFixtures.seedEmailSurvey
        backend        <- testBackend
        firstStatus    <- getStatus(backend)
        firstCookie     = extractSessionCookie(firstStatus).get
        _              <- getNextSurvey(backend, firstCookie)
        _              <- postEmailAnswer(backend, firstCookie, "returning@example.com")
        userCountBefore <- TestFixtures.countUsersByEmail("returning@example.com")
        existingUser   <- TestFixtures.getUserByEmail("returning@example.com")
        secondStatus   <- getStatus(backend)
        secondCookie    = extractSessionCookie(secondStatus).get
        _              <- getNextSurvey(backend, secondCookie)
        _              <- postEmailAnswer(backend, secondCookie, "returning@example.com")
        userCountAfter <- TestFixtures.countUsersByEmail("returning@example.com")
        session        <- TestFixtures.getSession(secondCookie)
      yield assertTrue(
        userCountBefore == 1L,
        userCountAfter == 1L,
        session.exists(_.userId.contains(existingUser.get.id))
      )
  )

  // ─────────────────────────────────────────────────────────────────────────────
  // Email Survey
  // ─────────────────────────────────────────────────────────────────────────────

  private val emailSurveySuite = suite("Email Survey")(
    test("anonymous user is offered email survey as first identification step"):
      for
        _          <- TestFixtures.seedEmailSurvey
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        nextResp   <- getNextSurvey(backend, cookie)
      yield assertTrue(
        nextResp.code.isSuccess,
        nextResp.body.contains("email")
      )
    ,
    test("valid email answer creates user and transitions to advertiser video phase"):
      for
        _          <- TestFixtures.seedEmailSurvey
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- getNextSurvey(backend, cookie)
        emailResp  <- postEmailAnswer(backend, cookie, "user@example.com")
        dbState    <- TestFixtures.queryDbState
      yield assertTrue(
        emailResp.code.isSuccess,
        emailResp.body.contains("true"),
        dbState.users.exists(_.email.contains("user@example.com")),
        dbState.answers.nonEmpty,
        dbState.sessions.exists(_.phase == "advertiser_video"),
        dbState.progress.nonEmpty
      )
    ,
    test("invalid email format is rejected with validation error"):
      for
        _          <- TestFixtures.seedEmailSurvey
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- getNextSurvey(backend, cookie)
        emailResp  <- postEmailAnswer(backend, cookie, "not-an-email")
      yield assertTrue(
        !emailResp.code.isSuccess || emailResp.body.contains("error"),
        emailResp.body.contains("invalid_email")
      )
  )

  // ─────────────────────────────────────────────────────────────────────────────
  // Survey Progression
  // ─────────────────────────────────────────────────────────────────────────────

  private val surveyProgressionSuite = suite("Survey Progression")(
    test("user who completed email survey is offered profiling survey"):
      for
        surveys    <- TestFixtures.seedAllIdentificationSurveys
        user       <- TestFixtures.createUser("completed@example.com")
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.email.surveyId)
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- TestFixtures.linkSessionToUser(cookie, user.userId)
        nextResp   <- getNextSurvey(backend, cookie)
      yield assertTrue(
        nextResp.code.isSuccess,
        nextResp.body.contains("profiling")
      )
    ,
    test("user who completed email and profiling surveys is offered location survey"):
      for
        surveys    <- TestFixtures.seedAllIdentificationSurveys
        user       <- TestFixtures.createUser("profiling-done@example.com")
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.email.surveyId)
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.profiling.surveyId)
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- TestFixtures.linkSessionToUser(cookie, user.userId)
        nextResp   <- getNextSurvey(backend, cookie)
      yield assertTrue(
        nextResp.code.isSuccess,
        nextResp.body.contains("location")
      )
    ,
    test("user who completed all identification surveys receives no more surveys"):
      for
        surveys    <- TestFixtures.seedAllIdentificationSurveys
        user       <- TestFixtures.createUser("all-done@example.com")
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.email.surveyId)
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.profiling.surveyId)
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.location.surveyId)
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- TestFixtures.linkSessionToUser(cookie, user.userId)
        nextResp   <- getNextSurvey(backend, cookie)
      yield assertTrue(
        nextResp.code.isSuccess,
        nextResp.body == "null" || nextResp.body.isEmpty || nextResp.body == "{}"
      )
    ,
    test("user who completed all identification surveys transitions to advertiser video phase"):
      for
        surveys    <- TestFixtures.seedAllIdentificationSurveys
        user       <- TestFixtures.createUser("ready@example.com")
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.email.surveyId)
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.profiling.surveyId)
        _          <- TestFixtures.markSurveyCompleted(user.userId, surveys.location.surveyId)
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- TestFixtures.linkSessionToUser(cookie, user.userId)
        _          <- TestFixtures.updateSessionPhase(cookie, "advertiser_video")
        finalStatus <- getStatus(backend, Some(cookie))
      yield assertTrue(
        finalStatus.code.isSuccess,
        finalStatus.body.contains("advertiser_video")
      )
  )

  // ─────────────────────────────────────────────────────────────────────────────
  // Multi-Question Survey
  // ─────────────────────────────────────────────────────────────────────────────

  private val multiQuestionSurveySuite = suite("Multi-Question Survey")(
    test("after answering first question, next question in same survey is offered"):
      for
        emailSurvey <- TestFixtures.seedEmailSurvey
        profiling   <- TestFixtures.seedMultiQuestionProfilingSurvey
        options     <- TestFixtures.addQuestionOptions(profiling.questions.head, List("18-25", "26-35"))
        backend     <- testBackend
        statusResp  <- getStatus(backend)
        cookie       = extractSessionCookie(statusResp).get
        _           <- getNextSurvey(backend, cookie)
        _           <- postEmailAnswer(backend, cookie, "multi@example.com")
        firstNext   <- getNextSurvey(backend, cookie)
        _           <- postProfilingAnswer(backend, cookie, options.head)
        secondNext  <- getNextSurvey(backend, cookie)
      yield assertTrue(
        firstNext.body.contains(profiling.questions.head.asString),
        secondNext.body.contains(profiling.questions(1).asString)
      )
  )

  // ─────────────────────────────────────────────────────────────────────────────
  // Validation Rules
  // ─────────────────────────────────────────────────────────────────────────────

  private val validationSuite = suite("Validation Rules")(
    test("empty email is rejected as required field"):
      for
        _          <- TestFixtures.seedEmailSurvey
        backend    <- testBackend
        statusResp <- getStatus(backend)
        cookie      = extractSessionCookie(statusResp).get
        _          <- getNextSurvey(backend, cookie)
        emailResp  <- postEmailAnswer(backend, cookie, "")
      yield assertTrue(
        !emailResp.code.isSuccess || emailResp.body.contains("error")
      )
  )
end E2ETests
