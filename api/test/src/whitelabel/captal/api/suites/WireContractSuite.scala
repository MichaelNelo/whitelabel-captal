package whitelabel.captal.api.suites

import io.circe.syntax.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.api.wire.given
import whitelabel.captal.core.application.Phase
import whitelabel.captal.messages.ApiError.given
import whitelabel.captal.messages.StatusResponse.given
import whitelabel.captal.messages.{ApiError, StatusResponse}
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

  private def zioWrites[A: Schema](value: A): String =
    JsonCodec.jsonEncoder(summon[Schema[A]]).encodeJson(value, None).toString

  private def zioReads[A: Schema](json: String): Either[String, A] = JsonCodec
    .jsonDecoder(summon[Schema[A]])
    .decodeJson(json)

  private def circeWrites[A: CirceEncoder](value: A): String = value.asJson.noSpaces

  private def circeReads[A: CirceDecoder](json: String): Either[String, A] = io
    .circe
    .parser
    .decode[A](json)
    .left
    .map(_.getMessage)

  /** Equal as JSON, counting an absent field and a `null` one as the same.
    *
    * Not string equality, because the two libraries disagree about exactly one thing and it is not
    * a disagreement anything can observe: circe's derived encoder writes `"redirectUrl":null` for a
    * `None` and zio-schema omits the field. Every decoder that reads these types reads an `Option`
    * from both — circe's `deriveDecoder` treats a missing field and a null alike, and so does
    * zio-schema — so the normalisation is the equivalence the two decoders already implement, not
    * a looser standard adopted to make the test pass.
    *
    * It still fails on everything that does matter: a renamed field, a changed discriminator, a
    * different casing, a `Phase` that stopped being `toDbString`. `ApiError` passes this test byte
    * for byte, having no `Option` fields to differ about.
    */
  private def sameJson(left: String, right: String): Boolean =
    val parsed = for
      l <- io.circe.parser.parse(left)
      r <- io.circe.parser.parse(right)
    yield l.deepDropNullValues == r.deepDropNullValues
    parsed.getOrElse(false)

  /** The three questions, asked of one type over a list of values that should cover its shape.
    *
    * One function because every type crossing this seam is asked the same thing, and because the
    * next route to convert should cost a list of values rather than a new suite.
    */
  private def agreement[A: Schema: CirceEncoder: CirceDecoder](
      what: String,
      values: List[A]): Spec[Any, Nothing] =
    zio
      .test
      .suite(s"$what: one wire format, zio-schema and circe")(
        test(s"the two write the same JSON for all ${values.size}"):
          val differing = values.flatMap: value =>
            val fromZio = zioWrites(value)
            val fromCirce = circeWrites(value)
            Option.when(!sameJson(fromZio, fromCirce))(
              s"$value\n        zio-schema: $fromZio\n        circe:      $fromCirce")
          assertTrue(differing.isEmpty) ?? differing.mkString("\n      ")
        ,
        test("what zio-schema writes, circe reads back to the same value"):
          // The response path. If this fails the browser cannot read what the server sent.
          val broken = values.flatMap: value =>
            circeReads[A](zioWrites(value)) match
              case Right(back) if back == value =>
                None
              case Right(other) =>
                Some(s"$value: circe read it back as $other")
              case Left(problem) =>
                Some(s"$value: circe could not read ${zioWrites(value)} — $problem")
          assertTrue(broken.isEmpty) ?? broken.mkString("\n      ")
        ,
        test("what circe writes, zio-schema reads back to the same value"):
          // The request path, which is what a converted route's `.in[A]` will depend on.
          val broken = values.flatMap: value =>
            zioReads[A](circeWrites(value)) match
              case Right(back) if back == value =>
                None
              case Right(other) =>
                Some(s"$value: zio-schema read it back as $other")
              case Left(problem) =>
                Some(s"$value: zio-schema could not read ${circeWrites(value)} — $problem")
          assertTrue(broken.isEmpty) ?? broken.mkString("\n      ")
      )

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

  /** Every `Phase`, and both `Option` fields each way.
    *
    * The phases matter more than they look: `Schema[Phase]` is hand-written to keep the seven
    * `toDbString` strings, because a derived enum schema would write `{"Welcome":{}}` and the
    * browser reads a plain string. These are what catch that if anyone replaces it with a
    * `DeriveSchema.gen`.
    *
    * The `Option`s matter for a different reason: circe's derived encoder writes `null` for a
    * `None`, and a codec that omits the field instead is a difference the first test sees — but one
    * circe's own decoder forgives, since a missing field and a null both decode to `None`. So the
    * byte test is the strict one here and the round trips are not; all three are worth having.
    */
  private val statuses: List[StatusResponse] =
    Phase.values.toList.map(phase => StatusResponse(phase, "es")) ++
      List(
        StatusResponse(Phase.Authorized, "en", Some("https://example.test/after"), Some(1800)),
        StatusResponse(Phase.Authorized, "es", None, Some(0)),
        StatusResponse(Phase.Ready, "en", Some("https://example.test/after"), None)
      )

  val suite: Spec[Any, Throwable] =
    zio
      .test
      .suite("Wire contract")(
        agreement("ApiError", every),
        agreement("StatusResponse", statuses))
end WireContractSuite
