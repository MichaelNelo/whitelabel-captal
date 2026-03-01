package whitelabel.captal.api.suites

import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object PhaseValidationSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio.test.suite("Phase Validation")(
      // ─────────────────────────────────────────────────────────────────────────
      // POST /api/survey/email - requires IdentificationQuestion
      // ─────────────────────────────────────────────────────────────────────────
      test("email endpoint rejects Welcome phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          // Session starts in Welcome phase
          response <- postEmailAnswer(backend, cookie, "test@example.com")
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"),
          response.body.contains("Welcome"),
          response.body.contains("IdentificationQuestion"))
      ,
      test("email endpoint rejects AdvertiserVideo phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.AdvertiserVideo)
          response <- postEmailAnswer(backend, cookie, "test@example.com")
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"),
          response.body.contains("AdvertiserVideo"))
      ,
      test("email endpoint rejects Ready phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Ready)
          response <- postEmailAnswer(backend, cookie, "test@example.com")
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"),
          response.body.contains("Ready"))
      ,
      test("email endpoint accepts IdentificationQuestion phase (no wrong_phase error)"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(
            user.SessionId.unsafe(cookie),
            Phase.IdentificationQuestion)
          response <- postEmailAnswer(backend, cookie, "test@example.com")
        yield assertTrue(!response.body.contains("wrong_phase"))
      ,
      // ─────────────────────────────────────────────────────────────────────────
      // POST /api/survey/profiling - requires IdentificationQuestion
      // ─────────────────────────────────────────────────────────────────────────
      test("profiling endpoint rejects Welcome phase"):
        for
          fixture <- TestFixtures.seedProfilingSurvey
          options <- TestFixtures.addQuestionOptions(fixture.questionId, List("18-25", "26-35"))
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          // Session starts in Welcome phase
          response <- postProfilingAnswer(backend, cookie, options.head.asString)
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"))
      ,
      test("profiling endpoint rejects AdvertiserVideo phase"):
        for
          fixture <- TestFixtures.seedProfilingSurvey
          options <- TestFixtures.addQuestionOptions(fixture.questionId, List("18-25", "26-35"))
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.AdvertiserVideo)
          response <- postProfilingAnswer(backend, cookie, options.head.asString)
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"))
      ,
      test("profiling endpoint accepts IdentificationQuestion phase (no wrong_phase error)"):
        for
          fixture <- TestFixtures.seedProfilingSurvey
          options <- TestFixtures.addQuestionOptions(fixture.questionId, List("18-25", "26-35"))
          userFixture <- TestFixtures.createUser("profiling-test@example.com")
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(
            user.SessionId.unsafe(cookie),
            Phase.IdentificationQuestion)
          _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), userFixture.userId)
          response <- postProfilingAnswer(backend, cookie, options.head.asString)
        yield assertTrue(!response.body.contains("wrong_phase"))
      ,
      // ─────────────────────────────────────────────────────────────────────────
      // GET /api/survey/next - requires Welcome OR IdentificationQuestion
      // ─────────────────────────────────────────────────────────────────────────
      test("nextSurvey endpoint accepts Welcome phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          // Session starts in Welcome phase
          response <- getNextSurvey(backend, cookie)
        yield assertTrue(response.code.isSuccess)
      ,
      test("nextSurvey endpoint accepts IdentificationQuestion phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(
            user.SessionId.unsafe(cookie),
            Phase.IdentificationQuestion)
          response <- getNextSurvey(backend, cookie)
        yield assertTrue(response.code.isSuccess)
      ,
      test("nextSurvey endpoint rejects AdvertiserVideo phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.AdvertiserVideo)
          response <- getNextSurvey(backend, cookie)
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"),
          response.body.contains("AdvertiserVideo"))
      ,
      test("nextSurvey endpoint rejects Ready phase"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Ready)
          response <- getNextSurvey(backend, cookie)
        yield assertTrue(
          response.code.code == 400,
          response.body.contains("wrong_phase"),
          response.body.contains("Ready"))
      ,
      // ─────────────────────────────────────────────────────────────────────────
      // GET /api/status - accepts any phase
      // ─────────────────────────────────────────────────────────────────────────
      test("status endpoint accepts Welcome phase"):
        for
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          response <- getStatus(backend, Some(cookie))
        yield assertTrue(response.code.isSuccess, response.body.contains("welcome"))
      ,
      test("status endpoint accepts IdentificationQuestion phase"):
        for
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(
            user.SessionId.unsafe(cookie),
            Phase.IdentificationQuestion)
          response <- getStatus(backend, Some(cookie))
        yield assertTrue(
          response.code.isSuccess,
          response.body.contains("identification_question"))
      ,
      test("status endpoint accepts AdvertiserVideo phase"):
        for
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.AdvertiserVideo)
          response <- getStatus(backend, Some(cookie))
        yield assertTrue(response.code.isSuccess, response.body.contains("advertiser_video"))
      ,
      test("status endpoint accepts Ready phase"):
        for
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Ready)
          response <- getStatus(backend, Some(cookie))
        yield assertTrue(response.code.isSuccess, response.body.contains("ready"))
    )
