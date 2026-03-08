package whitelabel.captal.api.suites

import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object SessionManagementSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Session Management")(
        test("status creates session in welcome phase"):
          for
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            verifyResp <- getStatus(backend, Some(cookie))
          yield assertTrue(
            statusResp.code.isSuccess,
            statusResp.body.contains("welcome"),
            verifyResp.body.contains("welcome"))
        ,
        test("getNextSurvey transitions phase from welcome to identification_question"):
          for
            _            <- TestFixtures.seedEmailSurvey
            backend      <- testBackend
            cookie       <- createSession(backend)
            statusBefore <- getStatus(backend, Some(cookie))
            _            <- getNextSurvey(backend, cookie)
            statusAfter  <- getStatus(backend, Some(cookie))
          yield assertTrue(
            statusBefore.body.contains("welcome"),
            statusAfter.body.contains("identification_question"))
        ,
        test("returning visitor with existing session maintains their phase"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(cookie),
              Phase.AdvertiserVideo)
            statusResp <- getStatus(backend, Some(cookie))
          yield assertTrue(statusResp.code.isSuccess, statusResp.body.contains("advertiser_video"))
        ,
        test(
          "lost session with existing user links to existing user instead of creating duplicate"):
          val testEmail = user.Email.unsafeFrom("returning@example.com")
          for
            _           <- TestFixtures.seedEmailSurvey
            backend     <- testBackend
            firstCookie <- createSession(backend)
            _               <- getNextSurvey(backend, firstCookie)
            _               <- postEmailAnswer(backend, firstCookie, "returning@example.com")
            userCountBefore <- TestFixtures.countUsersByEmail(testEmail)
            existingUser    <- TestFixtures.getUserByEmail(testEmail)
            // Simulate "lost session" - create new session
            secondCookie   <- createSession(backend)
            _              <- getNextSurvey(backend, secondCookie)
            _              <- postEmailAnswer(backend, secondCookie, "returning@example.com")
            userCountAfter <- TestFixtures.countUsersByEmail(testEmail)
            session        <- TestFixtures.getSession(user.SessionId.unsafe(secondCookie))
          yield assertTrue(
            userCountBefore == 1L,
            userCountAfter == 1L,
            session.exists(_.userId.contains(existingUser.get.id)))
      )
end SessionManagementSuite
