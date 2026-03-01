package whitelabel.captal.api.suites

import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object LocaleSessionSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio.test.suite("Locale and Session Endpoints")(
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
          statusResp.body.contains("welcome"))
      ,
      test("setLocale with existing session updates locale but preserves session state"):
        for
          backend    <- testBackend
          createResp <- putSetLocale(backend, "en")
          cookie = extractSessionCookie(createResp).get
          sessionId = user.SessionId.unsafe(cookie)
          _          <- TestFixtures.updateSessionPhase(sessionId, Phase.AdvertiserVideo)
          updateResp <- putSetLocale(backend, "es", Some(cookie))
          updatedCookie = extractSessionCookie(updateResp).get
          statusResp <- getStatus(backend, Some(updatedCookie))
        yield assertTrue(
          createResp.code.isSuccess,
          updateResp.code.isSuccess,
          cookie == updatedCookie,
          statusResp.body.contains("es"),
          statusResp.body.contains("advertiser_video"))
    )
