package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import zio.test.*

object ValidationSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Validation Rules")(
        // Named "required field" before this assertion checked which error came back: the old one
        // was `!code.isSuccess || body.contains("error")`, and the first disjunct is true for every
        // rejection, so the variant was never read. An empty string reaches the email format rule
        // and fails there, which is why this says `InvalidEmailFormat` and not
        // `RequiredAnswerMissing` — the request is refused either way.
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
