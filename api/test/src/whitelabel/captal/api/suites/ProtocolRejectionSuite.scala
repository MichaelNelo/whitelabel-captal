package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import zio.test.*

/** What the API does with a request body no client would send.
  *
  * Written before removing tapir, and that is the whole reason it exists. These rejections are not
  * ours: `.in(jsonBody[AnswerRequest])` carries a codec, and `ZioHttpInterpreter()` is constructed
  * with no options, so a body that fails to decode is answered by tapir's default
  * `DecodeFailureHandler` before any handler of ours runs. Its documentation says a query
  * parameter, header or body that causes a decode failure gets a 400.
  *
  * Nothing exercised that, ever. Every other helper in this suite's neighbourhood builds valid JSON
  * from a typed request, which is what makes them useful and also why an invalid one had never been
  * sent. So the behaviour was real and unverified at the same time: rewriting these endpoints onto
  * zio-http handlers would have to reimplement it explicitly, and if it did not, every test here
  * would still pass.
  *
  * That is what this pins. It is deliberately a record of what the current implementation does
  * rather than of what it should do — the five endpoints taking a body are the surface, and a
  * rewrite has to answer the same way.
  */
object ProtocolRejectionSuite:

  /** The three ways a body can be wrong, which are three different failures underneath.
    *
    * Not JSON at all is a parse failure; valid JSON of the wrong shape is a missing field; a valid
    * shape naming a variant that does not exist is a discriminator failure. A hand-written layer
    * can get one right and the others wrong, so each is asked for separately.
    */
  private val Malformed = "{"
  private val WrongShape = """{"notAnAnswer":true}"""
  private val UnknownVariant = """{"answer":{"type":"no_such_type","value":"x"}}"""

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Protocol rejections")(
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
          // `AnswerValue` than the server has. The discriminator is derived by the codec, so this
          // is the one of the three a hand-written decoder is most likely to accept by accident.
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- postRawBody(backend, cookie, "email", UnknownVariant)
          yield assertTrue(response.code.code == 400) ??
            s"unknown variant answered ${response.code.code}: ${response.body}"
      )
end ProtocolRejectionSuite
