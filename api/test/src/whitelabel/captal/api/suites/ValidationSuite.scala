package whitelabel.captal.api.suites

import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.api.TestHelpers.*
import zio.test.*

object ValidationSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio.test.suite("Validation Rules")(
      test("empty email is rejected as required field"):
        for
          _          <- TestFixtures.seedEmailSurvey
          backend    <- testBackend
          localeResp <- putSetLocale(backend, "es")
          cookie = extractSessionCookie(localeResp).get
          _         <- getNextSurvey(backend, cookie)
          emailResp <- postEmailAnswer(backend, cookie, "")
        yield assertTrue(!emailResp.code.isSuccess || emailResp.body.contains("error")))
