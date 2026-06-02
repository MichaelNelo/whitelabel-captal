package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object PhaseValidationSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Phase Validation")(
        // ─────────────────────────────────────────────────────────────────────────
        // POST /api/survey/email - requires IdentificationQuestion
        // ─────────────────────────────────────────────────────────────────────────
        test("email endpoint rejects Welcome phase"):
          for
            _          <- TestFixtures.seedEmailSurvey
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(cookie),
              Phase.AdvertiserVideo)
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _        <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Ready)
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
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
            backend <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            // Session starts in Welcome phase
            response <- postProfilingAnswer(backend, cookie, options.head.asString)
          yield assertTrue(response.code.code == 400, response.body.contains("wrong_phase"))
        ,
        test("profiling endpoint rejects AdvertiserVideo phase"):
          for
            fixture <- TestFixtures.seedProfilingSurvey
            options <- TestFixtures.addQuestionOptions(fixture.questionId, List("18-25", "26-35"))
            backend <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(cookie),
              Phase.AdvertiserVideo)
            response <- postProfilingAnswer(backend, cookie, options.head.asString)
          yield assertTrue(response.code.code == 400, response.body.contains("wrong_phase"))
        ,
        test("profiling endpoint accepts IdentificationQuestion phase (no wrong_phase error)"):
          for
            fixture <- TestFixtures.seedProfilingSurvey
            options <- TestFixtures.addQuestionOptions(fixture.questionId, List("18-25", "26-35"))
            userFixture <- TestFixtures.createUser("profiling-test@example.com")
            backend     <- testBackend
            statusResp  <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            // Session starts in Welcome phase
            response <- getNextSurvey(backend, cookie)
          yield assertTrue(response.code.isSuccess)
        ,
        test("nextSurvey endpoint accepts IdentificationQuestion phase"):
          for
            _          <- TestFixtures.seedEmailSurvey
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(cookie),
              Phase.AdvertiserVideo)
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _        <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Ready)
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            response <- getStatus(backend, Some(cookie))
          yield assertTrue(response.code.isSuccess, response.body.contains("welcome"))
        ,
        test("status endpoint accepts IdentificationQuestion phase"):
          for
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
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
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(cookie),
              Phase.AdvertiserVideo)
            response <- getStatus(backend, Some(cookie))
          yield assertTrue(response.code.isSuccess, response.body.contains("advertiser_video"))
        ,
        test("status endpoint accepts Ready phase"):
          for
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            _        <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Ready)
            response <- getStatus(backend, Some(cookie))
          yield assertTrue(response.code.isSuccess, response.body.contains("ready"))
        ,
        // ─────────────────────────────────────────────────────────────────────────
        // Authorized phase: action endpoints must reject so a finished user can't
        // re-trigger the flow. /api/status keeps working so the SPA can poll for
        // access expiration.
        // ─────────────────────────────────────────────────────────────────────────
        test("Authorized phase rejects all action endpoints but keeps /status polling open"):
          for
            _        <- TestFixtures.seedEmailSurvey
            _        <- TestFixtures.seedProfilingSurvey
            _        <- TestFixtures.seedLocationSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            _        <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Authorized)
            // Status — must still work (polling open)
            statusResp <- getStatus(backend, Some(cookie))
            // Action endpoints — all must reject with wrong_phase + "Authorized"
            nextSurveyResp <- getNextSurvey(backend, cookie)
            emailResp      <- postEmailAnswer(backend, cookie, "x@example.com")
            profilingResp  <- postProfilingAnswer(backend, cookie, "any-option")
            locationResp   <- postLocationAnswer(backend, cookie, "any-option")
            nextVideoResp  <- getNextVideo(backend, cookie)
            watchedResp    <- postMarkVideoWatched(backend, cookie, 10, completed = true)
            nextAdvResp    <- getNextAdvertiserSurvey(backend, cookie)
            advAnswerResp  <- postAdvertiserAnswer(backend, cookie, "any-option")
            finishResp     <- postFinish(backend, cookie)
          yield assertTrue(
            // /status — open
            statusResp.code.isSuccess,
            statusResp.body.contains("authorized"),
            // Each action — 400 wrong_phase mentioning current phase Authorized
            nextSurveyResp.code.code == 400,
            nextSurveyResp.body.contains("wrong_phase"),
            nextSurveyResp.body.contains("Authorized"),
            emailResp.code.code == 400,
            emailResp.body.contains("wrong_phase"),
            emailResp.body.contains("Authorized"),
            profilingResp.code.code == 400,
            profilingResp.body.contains("wrong_phase"),
            profilingResp.body.contains("Authorized"),
            locationResp.code.code == 400,
            locationResp.body.contains("wrong_phase"),
            locationResp.body.contains("Authorized"),
            nextVideoResp.code.code == 400,
            nextVideoResp.body.contains("wrong_phase"),
            nextVideoResp.body.contains("Authorized"),
            watchedResp.code.code == 400,
            watchedResp.body.contains("wrong_phase"),
            watchedResp.body.contains("Authorized"),
            nextAdvResp.code.code == 400,
            nextAdvResp.body.contains("wrong_phase"),
            nextAdvResp.body.contains("Authorized"),
            advAnswerResp.code.code == 400,
            advAnswerResp.body.contains("wrong_phase"),
            advAnswerResp.body.contains("Authorized"),
            finishResp.code.code == 400,
            finishResp.body.contains("wrong_phase"),
            finishResp.body.contains("Authorized")
          )
      )
end PhaseValidationSuite
