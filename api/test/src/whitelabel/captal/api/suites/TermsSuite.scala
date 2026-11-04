package whitelabel.captal.api.suites

import sttp.client3.*
import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.user
import zio.Task
import zio.test.*

/** The terms of service a device accepts before it leaves the welcome. Every test starts with
  * version 1 in es, en and pt (`TestFixtures.seedTerms`).
  */
object TermsSuite:

  /** Back to the welcome, as a returning guest's session is: the dev route resets the phase. */
  private def backToWelcome(backend: SttpBackend[Task, Any], cookie: String) = basicRequest
    .post(uri"http://test/api/dev/reset-phase")
    .cookie("captal_session", cookie)
    .response(asStringAlways)
    .send(backend)

  private def statusOf(backend: SttpBackend[Task, Any], cookie: String) = getStatus(
    backend,
    Some(cookie)).map(_.body)

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Terms of service")(
        test("a new device is asked to accept them, in the session's locale"):
          for
            backend <- testBackend
            status  <- getStatus(backend, None)
            cookie = extractSessionCookie(status).get
            terms <- getTerms(backend, cookie)
          yield assertTrue(
            status.body.contains("\"welcome_needs_tos\""),
            terms.code.isSuccess,
            terms.body.contains("Terms v1"))
        ,
        test("without accepting them, nothing leaves the welcome"):
          for
            backend <- testBackend
            cookie  <- createSessionAtWelcome(backend)
            started <- start(backend, cookie)
            survey  <- getNextSurvey(backend, cookie)
            after   <- statusOf(backend, cookie)
          yield assertTrue(
            started.code.code == 400,
            survey.code.code == 400,
            after.contains("\"welcome_needs_tos\""))
        ,
        test("accepting them leads to the identification questions and is kept on the device"):
          for
            backend  <- testBackend
            cookie   <- createSessionAtWelcome(backend)
            accepted <- acceptTerms(backend, cookie)
            after    <- statusOf(backend, cookie)
            state    <- TestFixtures.queryDbState
            devices  <- TestFixtures.getDevices
            deviceId = state.sessions.find(_.id == user.SessionId.unsafe(cookie)).map(_.deviceId)
          yield assertTrue(
            accepted.code.isSuccess,
            accepted.body.contains("identification_question"),
            after.contains("\"identification_question\""),
            devices.exists(d =>
              deviceId.contains(d.id) && d.termsId.isDefined && d.termsAcceptedAt.isDefined)
          )
        ,
        test("a device that accepted them starts without being asked again"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            _       <- backToWelcome(backend, cookie)
            welcome <- statusOf(backend, cookie)
            started <- start(backend, cookie)
          yield assertTrue(
            welcome.contains("\"welcome\""),
            started.code.isSuccess,
            started.body.contains("identification_question"))
        ,
        test("a new version is asked for again, even of a device that accepted the one before"):
          for
            backend  <- testBackend
            cookie   <- createSession(backend)
            _        <- TestFixtures.seedTerms(version = 2)
            _        <- backToWelcome(backend, cookie)
            asked    <- statusOf(backend, cookie)
            started  <- start(backend, cookie)
            terms    <- getTerms(backend, cookie)
            accepted <- acceptTerms(backend, cookie)
          yield assertTrue(
            asked.contains("\"welcome_needs_tos\""),
            started.code.code == 400,
            terms.body.contains("Terms v2"),
            accepted.code.isSuccess)
        ,
        test("a visitor with neither cookie is asked, whatever its device accepted"):
          for
            backend <- testBackend
            _       <- createSession(backend)
            again   <- getStatus(backend, None)
          yield assertTrue(again.body.contains("\"welcome_needs_tos\""))
        ,
        test("only the locales the terms are written in are offered"):
          for
            _        <- TestFixtures.seedLocales(List("es", "en", "pt", "fr"))
            backend  <- testBackend
            response <- getLocales(backend)
          yield assertTrue(
            response.code.isSuccess,
            response.body.contains("es"),
            response.body.contains("pt"),
            !response.body.contains("fr"))
      )
end TermsSuite
