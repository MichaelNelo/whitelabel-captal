package whitelabel.captal.api.suites

import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object SessionManagementSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio.test.suite("Session Management")(
      test("setLocale creates session in welcome phase"):
        for
          backend      <- testBackend
          localeResp   <- putSetLocale(backend, "es")
          sessionCookie = extractSessionCookie(localeResp).get
          statusResp   <- getStatus(backend, Some(sessionCookie))
        yield assertTrue(
          localeResp.code.isSuccess,
          statusResp.body.contains("welcome"))
      ,
      test("getNextSurvey transitions phase from welcome to identification_question"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          statusBefore <- getStatus(backend, Some(cookie))
          _            <- getNextSurvey(backend, cookie)
          statusAfter  <- getStatus(backend, Some(cookie))
        yield assertTrue(
          statusBefore.body.contains("welcome"),
          statusAfter.body.contains("identification_question"))
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
