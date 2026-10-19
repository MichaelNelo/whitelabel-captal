package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import zio.test.*

/** What the API does with a request body no client would send.
  *
  * Deliberately a record of what the implementation does rather than of what it should do: the five
  * endpoints taking a body are the surface, and a rewrite has to answer the same way.
  */
object ProtocolRejectionSuite:

  /** The three ways a body can be wrong, which are three different failures underneath: not JSON at
    * all is a parse failure; valid JSON of the wrong shape is a missing field; a valid shape naming
    * a variant that does not exist is a discriminator failure.
    */
  private val Malformed = "{"
  private val WrongShape = """{"notAnAnswer":true}"""
  private val UnknownVariant = """{"answer":{"type":"no_such_type","value":"x"}}"""

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Protocol rejections")(
        test("a route that no longer goes through tapir is still served to these suites"):
          // `/api/health` is on zio-http's `Endpoint`, so it reaches these suites only through the
          // adapter in `TestHelpers`.
          for
            backend  <- testBackend
            response <- getHealth(backend)
          yield assertTrue(response.code.isSuccess, response.body.contains("ok")) ??
            s"status=${response.code.code} body=${response.body}"
        ,
        test("a body that is not JSON is refused, and not by a handler of ours"):
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- postRawBody(backend, cookie, "email", Malformed)
          yield assertTrue(response.code.code == 400) ??
            s"not JSON answered ${response.code.code}: ${response.body}"
        ,
        test("valid JSON missing the field the request requires is refused"):
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- postRawBody(backend, cookie, "email", WrongShape)
          yield assertTrue(response.code.code == 400) ??
            s"wrong shape answered ${response.code.code}: ${response.body}"
        ,
        test("an answer naming a type that does not exist is refused"):
          // Closest to something that could actually happen: a client built against a newer
          // `AnswerValue` than the server has.
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- postRawBody(backend, cookie, "email", UnknownVariant)
          yield assertTrue(response.code.code == 400) ??
            s"unknown variant answered ${response.code.code}: ${response.body}"
      )
end ProtocolRejectionSuite
