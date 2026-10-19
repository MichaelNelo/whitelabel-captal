package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import zio.test.*

object ValidationSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Validation Rules")(
        // An empty string reaches the email format rule and fails there, which is why this expects
        // `InvalidEmailFormat` and not `RequiredAnswerMissing`.
        test("empty email is rejected as a malformed address"):
          for
            _         <- TestFixtures.seedEmailSurvey
            backend   <- testBackend
            cookie    <- createSession(backend)
            _         <- getNextSurvey(backend, cookie)
            emailResp <- postEmailAnswer(backend, cookie, "")
          yield assertTrue(
            emailResp.code.code == 400,
            emailResp.body.contains("InvalidEmailFormat")))
end ValidationSuite
