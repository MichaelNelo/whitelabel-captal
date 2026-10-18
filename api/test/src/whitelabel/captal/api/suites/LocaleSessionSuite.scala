package whitelabel.captal.api.suites

import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object LocaleSessionSuite:

  /** A `Set-Cookie` header's attributes, lowercased, without the name=value pair itself.
    *
    * Lowercased because the casing is the one thing here that genuinely does not matter: `Path` and
    * `path` are the same attribute to a browser, and the two libraries need not agree on which to
    * write.
    */
  private def attributesOf(header: String): Set[String] = header
    .split(";")
    .drop(1)
    .map(_.trim.toLowerCase)
    .filter(_.nonEmpty)
    .toSet

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Locale and Session Endpoints")(
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
        test("GET /api/status without session creates new session"):
          for
            countBefore <- TestFixtures.countSessions
            backend     <- testBackend
            response    <- getStatus(backend, None)
            countAfter  <- TestFixtures.countSessions
            cookie = extractSessionCookie(response)
          yield assertTrue(
            response.code.isSuccess,
            response.body.contains("welcome"),
            response.body.contains("es"), // default locale
            cookie.isDefined,
            countAfter == countBefore + 1)
        ,
        test("PUT /api/session/locale without session returns error"):
          for
            backend  <- testBackend
            response <- putSetLocale(backend, "es")
          yield assertTrue(!response.code.isSuccess, response.body.contains("SessionMissing"))
        ,
        test("PUT /api/session/locale with existing session updates locale"):
          for
            backend <- testBackend
            // First call status to create session
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            countBefore <- TestFixtures.countSessions
            // Then update locale
            localeResp <- putSetLocale(backend, "en", Some(cookie))
            updatedCookie = extractSessionCookie(localeResp).get
            countAfter <- TestFixtures.countSessions
          yield assertTrue(
            statusResp.code.isSuccess,
            localeResp.code.isSuccess,
            cookie == updatedCookie,
            localeResp.body.contains("en"),
            countAfter == countBefore
          ) // No new session created
        ,
        test("the cookie a converted route sets is the same cookie the others set"):
          // `extractSessionCookie` reads the name and value and stops at the first `;`, so the
          // attributes have never been asserted — and `PUT /api/session/locale` is now the one
          // route writing them from zio-http's `Cookie.Response` rather than tapir's
          // `CookieValueWithMeta`. Name and path are what the browser keys an entry on: two routes
          // disagreeing about either would be writing to two separate entries, and a guest would
          // appear to lose their session on changing language.
          for
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            localeResp <- putSetLocale(backend, "en", Some(cookie))
            fromTapir = statusResp.header("Set-Cookie").map(attributesOf)
            fromConverted = localeResp.header("Set-Cookie").map(attributesOf)
          yield assertTrue(fromConverted == fromTapir, fromConverted.contains(Set("path=/"))) ??
            s"tapir=${statusResp.header("Set-Cookie")} converted=${localeResp.header("Set-Cookie")}"
        ,
        test("status creates session, setLocale updates same session"):
          for
            backend    <- testBackend
            statusResp <- getStatus(backend, None)
            cookie = extractSessionCookie(statusResp).get
            localeResp      <- putSetLocale(backend, "en", Some(cookie))
            finalStatusResp <- getStatus(backend, Some(cookie))
          yield assertTrue(
            statusResp.code.isSuccess,
            localeResp.code.isSuccess,
            finalStatusResp.code.isSuccess,
            finalStatusResp.body.contains("en"),
            finalStatusResp.body.contains("welcome")
          )
        ,
        test("setLocale with existing session updates locale but preserves session state"):
          for
            backend <- testBackend
            // Create session via status
            statusResp <- getStatus(backend, None)
            cookie    = extractSessionCookie(statusResp).get
            sessionId = user.SessionId.unsafe(cookie)
            // Update phase directly
            _ <- TestFixtures.updateSessionPhase(sessionId, Phase.AdvertiserVideo)
            // Update locale
            updateResp <- putSetLocale(backend, "pt", Some(cookie))
            updatedCookie = extractSessionCookie(updateResp).get
            finalResp <- getStatus(backend, Some(updatedCookie))
          yield assertTrue(
            statusResp.code.isSuccess,
            updateResp.code.isSuccess,
            cookie == updatedCookie,
            finalResp.body.contains("pt"),
            finalResp.body.contains("advertiser_video"))
      )
end LocaleSessionSuite
