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
            response.body.contains("WrongPhase"),
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
            response.body.contains("WrongPhase"),
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
            response.body.contains("WrongPhase"),
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
          yield assertTrue(!response.body.contains("WrongPhase"))
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
          yield assertTrue(response.code.code == 400, response.body.contains("WrongPhase"))
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
          yield assertTrue(response.code.code == 400, response.body.contains("WrongPhase"))
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
          yield assertTrue(!response.body.contains("WrongPhase"))
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
            response.body.contains("WrongPhase"),
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
            response.body.contains("WrongPhase"),
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
            _       <- TestFixtures.seedEmailSurvey
            _       <- TestFixtures.seedProfilingSurvey
            _       <- TestFixtures.seedLocationSurvey
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.updateSessionPhase(user.SessionId.unsafe(cookie), Phase.Authorized)
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
            nextSurveyResp.body.contains("WrongPhase"),
            nextSurveyResp.body.contains("Authorized"),
            emailResp.code.code == 400,
            emailResp.body.contains("WrongPhase"),
            emailResp.body.contains("Authorized"),
            profilingResp.code.code == 400,
            profilingResp.body.contains("WrongPhase"),
            profilingResp.body.contains("Authorized"),
            locationResp.code.code == 400,
            locationResp.body.contains("WrongPhase"),
            locationResp.body.contains("Authorized"),
            nextVideoResp.code.code == 400,
            nextVideoResp.body.contains("WrongPhase"),
            nextVideoResp.body.contains("Authorized"),
            watchedResp.code.code == 400,
            watchedResp.body.contains("WrongPhase"),
            watchedResp.body.contains("Authorized"),
            nextAdvResp.code.code == 400,
            nextAdvResp.body.contains("WrongPhase"),
            nextAdvResp.body.contains("Authorized"),
            advAnswerResp.code.code == 400,
            advAnswerResp.body.contains("WrongPhase"),
            advAnswerResp.body.contains("Authorized"),
            finishResp.code.code == 400,
            finishResp.body.contains("WrongPhase"),
            finishResp.body.contains("Authorized")
          )
        ,
        test(
          "with no identification survey, an unidentified visitor ends at Ready, not at a video"):
          // The flow trades access for data. With nothing able to identify the visitor, carrying on
          // to the advertiser video would hand out the access and collect nothing — and the session
          // would reach it with user_id still NULL, since only UserCreated ever sets it.
          //
          // Retiring the last active email question is now a single call, so this is reachable by
          // an operator, not just by a misprovisioned deployment.
          for
            advertiser <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 1)
            backend    <- testBackend
            cookie     <- createSession(backend)
            nextResp   <- getNextSurvey(backend, cookie)
            dbState    <- TestFixtures.queryDbState
          yield assertTrue(
            nextResp.code.isSuccess,
            // No survey to serve: a step, not a question.
            parseNextSurvey(nextResp.body).isEmpty,
            nextResp.body.contains("\"phase\":\"ready\""),
            !nextResp.body.contains("advertiser_video"),
            dbState.sessions.exists(_.phase == Phase.Ready),
            // And crucially, no video was assigned on the way.
            dbState.sessions.forall(_.currentVideoId.isEmpty),
            advertiser.questionIds.nonEmpty
          )
      )
end PhaseValidationSuite
