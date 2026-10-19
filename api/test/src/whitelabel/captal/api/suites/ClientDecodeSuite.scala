package whitelabel.captal.api.suites

import io.circe.Decoder
import io.circe.parser.decode
import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.messages.*
import whitelabel.captal.messages.ApiError.given
import whitelabel.captal.messages.SurveyResponse.given
import zio.test.*

/** Every response body a real request produced, decoded with the instance the browser decodes it
  * with.
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
  */
object ClientDecodeSuite:

  /** Decodes, putting the body in the failure message as well as the error. */
  private def readsAs[A: Decoder](label: String, body: String): TestResult =
    assertTrue(decode[A](body).isRight) ??
      s"$label did not decode: ${decode[A](body).left.map(_.getMessage)}\n      body: $body"

  /** Every `ApiError` the API can answer with. A variant that fails to round-trip surfaces to the
    * guest as "HTTP 400": `ApiClient` falls back to `InternalError(s"HTTP ${status}: $text")` when
    * `decode[ApiError]` fails.
    *
    * Listed by hand, since a parameterised enum cannot be enumerated at runtime: a new variant
    * nobody adds here is a variant nobody tested.
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
          // No HTTP: a request would only reach whichever variants its endpoint happens to
          // produce.
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
        test("the same type off a second route that also returns it"):
          // Same type, two producers: `/api/status` builds one from a session it may have just
          // created, `/api/finish` re-reads the session after the flow committed.
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            _        <- getNextSurvey(backend, cookie)
            _        <- postEmailAnswer(backend, cookie, "converted-route@example.com")
            _        <- getNextVideo(backend, cookie)
            response <- postFinish(backend, cookie)
          yield readsAs[StatusResponse]("StatusResponse (finish)", response.body) &&
            assertTrue(response.code.isSuccess) ??
            s"status=${response.code.code} body=${response.body}"
        ,
        test("the next identification survey, as the wrapper the endpoint returns"):
          // `SurveyResponse` and not `NextIdentificationSurvey`: the other suites decode the
          // payload directly and skip the discriminated wrapper, which is hand-written on both
          // sides.
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- getNextSurvey(backend, cookie)
          yield readsAs[SurveyResponse]("SurveyResponse", response.body)
        ,
        test("the step variant of that wrapper, which takes the other branch"):
          // No survey seeded, so the endpoint answers with a phase rather than a question: a
          // discriminator that encodes one variant correctly can still lose the other.
          for
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- getNextSurvey(backend, cookie)
          yield readsAs[SurveyResponse]("SurveyResponse (step)", response.body)
        ,
        test("an error body"):
          // `/api/finish` in Welcome, read as the type the SPA turns into a message for a guest.
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
