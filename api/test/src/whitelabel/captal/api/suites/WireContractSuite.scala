package whitelabel.captal.api.suites

import io.circe.syntax.*
import whitelabel.captal.messages.ApiError
import whitelabel.captal.messages.ApiError.given
import zio.schema.codec.JsonCodec
import zio.schema.{DeriveSchema, Schema}
import zio.test.*

/** One wire format, two libraries, checked in both directions.
  *
  * The server will serialise with zio-schema — zio-http's `Endpoint` API requires it and it is not
  * pluggable — and the browser stays on circe, because putting zio-schema in `client` means a new
  * runtime dependency in a bundle this project measures. So two libraries write and read one
  * format, and nothing but this makes them agree.
  *
  * Both directions, because agreement is not symmetric. An encoder can emit a field a decoder
  * ignores and the round trip still looks fine from the side that wrote it; the failure only
  * appears when the other side reads it. So:
  *
  *   - zio-schema writes it, circe reads it — the response path, server to browser.
  *   - circe writes it, zio-schema reads it — the request path, browser to server.
  *
  * This is the test the previous arrangement did not have, and the cost of not having it was
  * measured: `ApiError`'s circe pair was written by hand in two independent matches, the encoder
  * covering all thirty-two variants because the compiler forces it and the decoder covering nine
  * because a match on a `String` with a catch-all is always exhaustive. Twenty-two validation
  * errors reached the browser as `InternalError("Unknown error type: …")` and sent the guest to the
  * error screen. Both sides are derived now, which makes that gap unrepresentable — and this suite
  * is what keeps the two derivations describing the same bytes.
  */
object WireContractSuite:

  private given Schema[ApiError] = DeriveSchema.gen[ApiError]

  private def zioWrites(error: ApiError): String =
    JsonCodec.jsonEncoder(summon[Schema[ApiError]]).encodeJson(error, None).toString

  private def zioReads(json: String): Either[String, ApiError] = JsonCodec
    .jsonDecoder(summon[Schema[ApiError]])
    .decodeJson(json)

  private def circeWrites(error: ApiError): String = error.asJson.noSpaces

  private def circeReads(json: String): Either[String, ApiError] = io
    .circe
    .parser
    .decode[ApiError](json)
    .left
    .map(_.getMessage)

  /** Every variant, so a format difference cannot hide in the ones no endpoint happens to return.
    *
    * Listed rather than generated: an enum carrying parameters cannot be enumerated at runtime, and
    * a generator would have to be told each shape anyway. A variant nobody adds here is a variant
    * nobody checked, which is the same bargain the type already makes with its own exhaustiveness.
    */
  private val every: List[ApiError] = List(
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

  private def label(error: ApiError): String = error.getClass.getSimpleName.stripSuffix("$")

  val suite: Spec[Any, Throwable] =
    zio
      .test
      .suite("ApiError: one wire format, zio-schema and circe")(
        test(s"the two write the same bytes for all ${every.size} variants"):
          val differing = every.flatMap: error =>
            val zio = zioWrites(error)
            val circe = circeWrites(error)
            Option.when(zio != circe)(
              s"${label(error)}\n        zio-schema: $zio\n        circe:      $circe")
          assertTrue(differing.isEmpty) ?? differing.mkString("\n      ")
        ,
        test("what zio-schema writes, circe reads back to the same value"):
          // The response path. If this fails the browser cannot read an error the server sent.
          val broken = every.flatMap: error =>
            circeReads(zioWrites(error)) match
              case Right(back) if back == error =>
                None
              case Right(other) =>
                Some(s"${label(error)}: circe read it back as $other")
              case Left(problem) =>
                Some(s"${label(error)}: circe could not read ${zioWrites(error)} — $problem")
          assertTrue(broken.isEmpty) ?? broken.mkString("\n      ")
        ,
        test("what circe writes, zio-schema reads back to the same value"):
          // The request path. `ApiError` does not travel this way, but the types that do are
          // derived the same way, so this is the direction the request bodies will depend on.
          val broken = every.flatMap: error =>
            zioReads(circeWrites(error)) match
              case Right(back) if back == error =>
                None
              case Right(other) =>
                Some(s"${label(error)}: zio-schema read it back as $other")
              case Left(problem) =>
                Some(s"${label(error)}: zio-schema could not read ${circeWrites(error)} — $problem")
          assertTrue(broken.isEmpty) ?? broken.mkString("\n      ")
      )
end WireContractSuite
