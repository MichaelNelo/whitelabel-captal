package whitelabel.captal.api.suites

import io.circe.Decoder
import io.circe.parser.decode
import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.messages.*
import whitelabel.captal.messages.ApiError.given
import whitelabel.captal.messages.SurveyResponse.given
import zio.test.*

/** Every response body, decoded with the instance the browser decodes it with.
  *
  * The question behind this is whether the SPA can read what the server writes, and the honest way
  * to ask it is not to compare two serialisations as strings — it is to take the body a real
  * request produced and hand it to the client's own decoder.
  *
  * `ApiClient` itself cannot be run here: it is Scala.js, `client` has no test module, and
  * `fetchApi` goes through `dom.fetch` and `dom.window.location`. But the only thing it does with a
  * body is three calls —
  *
  * parser.decode[A](text) // 2xx isPaused(text) // the load balancer's paused body
  * parser.decode[ApiError](text) // everything else
  *
  * — and all three resolve the `Decoder` instances in `core.codecs`, which cross-compile. So the
  * decoding this exercises is the same code the browser runs, byte for byte. What it does not cover
  * is the fetch, the status branching and the `InternalError` fallback, which are browser concerns.
  *
  * Written for the migration off tapir, and worth keeping after it. The server will serialise with
  * zio-schema if the route descriptions move to zio-http's `Endpoint` API, and circe stays in the
  * browser — two libraries writing one format. This is what stops them drifting, and the assertions
  * that came before it compared substrings: `body.contains("WrongPhase")` passes against a body
  * whose shape has changed around that word.
  */
object ClientDecodeSuite:

  /** Decodes, and says what it was looking at when it could not.
    *
    * The body in the failure message rather than just the error: a format change reads as "expected
    * X, got Y" with no indication of what arrived, and the first thing anyone wants is the JSON.
    */
  private def readsAs[A: Decoder](label: String, body: String): TestResult =
    assertTrue(decode[A](body).isRight) ??
      s"$label did not decode: ${decode[A](body).left.map(_.getMessage)}\n      body: $body"

  /** Every `ApiError` the API can answer with.
    *
    * All thirty-two, because the encoder and the decoder are written by hand *separately* — one
    * `match` producing a type tag and a payload, another reading the tag and rebuilding the case —
    * and nothing checked that the two agree. A variant whose encoder names a field the decoder does
    * not read round-trips to a different value, or fails to round-trip at all, and the only place
    * it would surface is a guest seeing "HTTP 400" where a message was meant to be: `ApiClient`
    * falls back to `InternalError(s"HTTP ${status}: $text")` when `decode[ApiError]` fails.
    *
    * Listed rather than generated. An enum with parameters cannot be enumerated at runtime, and a
    * generator would have to be told each shape anyway — at which point the list is the honest
    * version, and a new variant that nobody adds here is a variant nobody tested.
    */
  private val everyApiError: List[ApiError] = List(
    ApiError.SessionMissing,
    ApiError.SessionInvalid("malformed"),
    ApiError.SessionExpired,
    ApiError.WrongPhase("Welcome", List("Ready")),
    ApiError.NoSurveyAssigned,
    ApiError.UserNotIdentified,
    ApiError.InvalidEmailFormat("not-an-email"),
    ApiError.RequiredAnswerMissing("q1"),
    ApiError.InvalidOptionSelected("q1", "o1"),
    ApiError.InvalidOptionsSelected("q1", List("o1", "o2")),
    ApiError.TooFewSelections("q1", 2, 1),
    ApiError.TooManySelections("q1", 2, 3),
    ApiError.TextTooShort("q1", 5, 2),
    ApiError.TextTooLong("q1", 5, 9),
    ApiError.InvalidPattern("q1", "^a+$"),
    ApiError.InvalidEmail("q1"),
    ApiError.InvalidUrl("q1"),
    ApiError.RatingOutOfRange("q1", 1.0f, 5.0f, 9.0f),
    ApiError.NumericOutOfRange("q1", "1", "5", "9"),
    ApiError.DateOutOfRange("q1", "2020-01-01", "2020-12-31", "2021-06-01"),
    ApiError.IncompatibleAnswerType("q1", "text"),
    ApiError.QuestionAlreadyAnswered("q1"),
    ApiError.QuestionNotFound("q1"),
    ApiError.HierarchyViolation("q1", "state"),
    ApiError.EmailAlreadyRegistered("u1"),
    ApiError.UserSessionExpired("s1"),
    ApiError.NoPendingQuestions("u1"),
    ApiError.QuestionNotPending("u1", "q1"),
    ApiError.VideoNotFound("v1"),
    ApiError.NoVideoAvailable,
    ApiError.LocationNotFound("cafe-centro"),
    ApiError.InternalError("something gave way")
  )

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("The browser's decoders against real responses")(
        test(s"all ${everyApiError.size} ApiError variants survive the round trip"):
          // No HTTP: the question is whether the hand-written pair agrees with itself, and a
          // request would only reach whichever variants its endpoint happens to produce.
          val broken = everyApiError.flatMap: error =>
            val json = io.circe.syntax.EncoderOps(error).asJson.noSpaces
            decode[ApiError](json) match
              case Right(back) if back == error =>
                None
              case Right(other) =>
                Some(s"${error.getClass.getSimpleName}: encoded $json, decoded back as $other")
              case Left(problem) =>
                Some(
                  s"${error.getClass.getSimpleName}: encoded $json, did not decode — ${problem
                      .getMessage}")
          assertTrue(broken.isEmpty) ?? broken.mkString("\n      ")
        ,
        test("a status response"):
          for
            backend  <- testBackend
            response <- getStatus(backend, None)
          yield readsAs[StatusResponse]("StatusResponse", response.body)
        ,
        test("the next identification survey, as the wrapper the endpoint returns"):
          // `SurveyResponse` and not `NextIdentificationSurvey`: the suites decode the payload
          // directly, which skips the discriminated wrapper — the one part of this format that is
          // hand-written on both sides and the one most likely to move.
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- getNextSurvey(backend, cookie)
          yield readsAs[SurveyResponse]("SurveyResponse", response.body)
        ,
        test("the step variant of that wrapper, which takes the other branch"):
          // No survey seeded, so the endpoint answers with a phase rather than a question. Both
          // variants matter: a discriminator that encodes one correctly can still lose the other.
          for
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- getNextSurvey(backend, cookie)
          yield readsAs[SurveyResponse]("SurveyResponse (step)", response.body)
        ,
        test("an error body"):
          // `/api/finish` in Welcome is the refusal `PhaseValidationSuite` already asserts on, but
          // by substring. This reads it as the type the SPA turns into a message for a guest.
          for
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- postFinish(backend, cookie)
          yield readsAs[ApiError]("ApiError", response.body) && assertTrue(!response.code.isSuccess)
        ,
        test("the locale list and a translation bundle"):
          for
            backend <- testBackend
            locales <- getLocales(backend)
          yield readsAs[List[String]]("List[String]", locales.body)
      )
end ClientDecodeSuite
