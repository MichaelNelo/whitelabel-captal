package whitelabel.captal.api.suites

import java.time.LocalDate

import io.circe.syntax.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.api.HttpApp
import whitelabel.captal.api.wire.given
import whitelabel.captal.core.application.commands.{NextIdentificationSurvey, NextVideo}
import whitelabel.captal.core.application.{IdentificationSurveyType, NextStep, Phase}
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.question.{
  AnswerValue,
  CommonRule,
  LocalizedText,
  OptionId,
  QuestionOption,
  QuestionToAnswer,
  QuestionType,
  RangeRule,
  SelectionRule,
  TextRule
}
import whitelabel.captal.messages.ApiError.given
import whitelabel.captal.messages.MarkVideoWatchedRequest.given
import whitelabel.captal.messages.SetLocaleRequest.given
import whitelabel.captal.messages.StatusResponse.given
import whitelabel.captal.messages.SurveyResponse.given
import whitelabel.captal.messages.VideoResponse.given
import whitelabel.captal.messages.VideoWatchedResponse.given
import whitelabel.captal.messages.i18n.given
import whitelabel.captal.messages.{
  ApiError,
  MarkVideoWatchedRequest,
  SetLocaleRequest,
  StatusResponse,
  SurveyResponse,
  VideoResponse,
  VideoWatchedResponse
}
import whitelabel.captal.platform.surveySchemas.given
import zio.schema.codec.JsonCodec
import zio.schema.{DeriveSchema, Schema}
import zio.test.*

/** One wire format, two libraries, checked in both directions.
  *
  * The server will serialise with zio-schema — zio-http's `Endpoint` API requires it and it is not
  * pluggable — and the browser stays on circe. So two libraries write and read one format, and
  * nothing but this makes them agree.
  *
  * Both directions, because agreement is not symmetric. An encoder can emit a field a decoder
  * ignores and the round trip still looks fine from the side that wrote it; the failure only
  * appears when the other side reads it. So:
  *
  *   - zio-schema writes it, circe reads it — the response path, server to browser.
  *   - circe writes it, zio-schema reads it — the request path, browser to server.
  *
  * Both sides are derived, and this suite is what keeps the two derivations describing the same
  * bytes.
  */
object WireContractSuite:

  private given Schema[ApiError] = DeriveSchema.gen[ApiError]

  /** The server's own config, read from `Main` rather than restated here, because it is part of the
    * contract: zio-schema defaults to `ignoreEmptyCollections = false` and zio-http's `CodecConfig`
    * to `true`, so a copy of the value would drift from the server the first time one changed.
    */
  private val config = JsonCodec.Config(ignoreEmptyCollections =
    HttpApp.codecConfig.ignoreEmptyCollections)

  private def zioWrites[A: Schema](value: A): String =
    JsonCodec.jsonEncoder(config)(summon[Schema[A]]).encodeJson(value, None).toString

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
    * Not string equality: circe's derived encoder writes `"redirectUrl":null` for a `None` and
    * zio-schema omits the field, while every decoder that reads these types treats a missing field
    * and a null alike. So the normalisation is the equivalence the two decoders already implement.
    *
    * It still fails on a renamed field, a changed discriminator, a different casing, or a `Phase`
    * that stopped being `toDbString`. `ApiError` passes byte for byte, having no `Option` fields.
    */
  private def sameJson(left: String, right: String): Boolean =
    val parsed =
      for
        l <- io.circe.parser.parse(left)
        r <- io.circe.parser.parse(right)
      yield l.deepDropNullValues == r.deepDropNullValues
    parsed.getOrElse(false)

  /** The three questions, asked of one type over a list of values that should cover its shape. */
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
          // The request path, which is what a route's `.in[A]` depends on.
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
    * Listed by hand, since a parameterised enum cannot be enumerated at runtime: a variant nobody
    * adds here is a variant nobody checked.
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
    * `Schema[Phase]` is hand-written to keep the seven `toDbString` strings: a derived enum schema
    * would write `{"Welcome":{}}` and the browser reads a plain string, and these values are what
    * catch a replacement by `DeriveSchema.gen`.
    *
    * The `Option`s are caught by the byte test alone: circe writes `null` for a `None` and a codec
    * that omits the field is a difference circe's own decoder forgives.
    */
  private val statuses: List[StatusResponse] =
    Phase.values.toList.map(phase => StatusResponse(phase, "es")) ++
      List(
        StatusResponse(Phase.Authorized, "en", Some("https://example.test/after"), Some(1800)),
        StatusResponse(Phase.Authorized, "es", None, Some(0)),
        StatusResponse(Phase.Ready, "en", Some("https://example.test/after"), None)
      )

  /** A bundle whose every field holds its own name, so a schema that pairs the wrong name with the
    * wrong value is visible rather than symmetric. Thirty-eight strings, none interchangeable.
    */
  private val bundle = I18n(
    welcome = I18n.Welcome(
      title = "welcome.title",
      subtitle = "welcome.subtitle",
      steps = I18n.Welcome.Steps("steps.1", "steps.2", "steps.3"),
      button = I18n.Welcome.Button("button.start", "button.connecting"),
      selectLanguage = "welcome.selectLanguage",
      authorized = I18n.Welcome.Authorized("authorized.title", "authorized.remaining")
    ),
    ready = I18n.Ready("ready.title", "ready.subtitle", "ready.resetButton"),
    loading = I18n.Loading("loading.message"),
    error = I18n.Error("error.title", "error.retry", "error.generic"),
    question = I18n.Question(
      submit = "question.submit",
      next = "question.next",
      required = "question.required",
      invalidEmail = "question.invalidEmail",
      invalidUrl = "question.invalidUrl",
      invalidPattern = "question.invalidPattern",
      minLength = "question.minLength",
      maxLength = "question.maxLength",
      minSelections = "question.minSelections",
      maxSelections = "question.maxSelections",
      invalidOption = "question.invalidOption",
      ratingOutOfRange = "question.ratingOutOfRange",
      numericOutOfRange = "question.numericOutOfRange",
      dateOutOfRange = "question.dateOutOfRange",
      invalidAnswer = "question.invalidAnswer"
    ),
    video = I18n.Video(
      pageTitle = "video.pageTitle",
      continueIn = "video.continueIn",
      watchComplete = "video.watchComplete",
      markWatched = "video.markWatched",
      loading = "video.loading",
      noVideoAvailable = "video.noVideoAvailable",
      payAttention = "video.payAttention"
    )
  )

  private val anOptionId = OptionId.unsafe("22222222-2222-2222-2222-222222222222")

  private val option = QuestionOption(
    anOptionId,
    LocalizedText("Caracas", "es"),
    displayOrder = 1,
    parentOptionId = None)

  /** One question with every optional field filled and every one left empty is two questions, so
    * this is the filled one and `QuestionType.Input` carries the empty-rule-list case above.
    */
  private val aQuestion = QuestionToAnswer(
    id = question.Id.unsafe("33333333-3333-3333-3333-333333333333"),
    text = LocalizedText("¿Cuál es tu correo?", "es"),
    description = Some(LocalizedText("Lo usamos para reconocerte", "es")),
    placeholder = Some(LocalizedText("tu@correo.com", "es")),
    questionType = QuestionType.Input(List(TextRule.Email)),
    commonRules = List(CommonRule.Required),
    pointsAwarded = 10
  )

  /** The same question with options rather than rules, and both `Option` fields empty: an
    * options-bearing question nested inside `QuestionToAnswer` inside `SurveyResponse`, which is
    * what every profiling and location survey returns.
    */
  private val aRadioQuestion = QuestionToAnswer(
    id = question.Id.unsafe("55555555-5555-5555-5555-555555555555"),
    text = LocalizedText("¿Dónde vives?", "es"),
    description = None,
    placeholder = None,
    questionType = QuestionType.Radio(List(option)),
    commonRules = Nil,
    pointsAwarded = 0
  )

  /** The same question with no options at all: a real state — a radio question whose options have
    * all been retired — and the empty-`List` case the two libraries differ on, which every fixture
    * above fills.
    */
  private val anOptionlessQuestion = aRadioQuestion.copy(questionType = QuestionType.Radio(Nil))

  val suite: Spec[Any, Throwable] =
    zio
      .test
      .suite("Wire contract")(
        agreement("ApiError", every),
        agreement("StatusResponse", statuses),
        agreement("SetLocaleRequest", List(SetLocaleRequest("es"), SetLocaleRequest("en"))),
        agreement("I18n", List(bundle)),
        // Both branches of the discriminated response, and `NextVideo`'s two `Option` fields each
        // way.
        agreement(
          "VideoResponse",
          List(
            VideoResponse.Video(
              NextVideo("https://cdn.test/a.mp4", 30, Some("Title"), Some("Desc"))),
            VideoResponse.Video(NextVideo("https://cdn.test/b.mp4", 15, None, None)),
            VideoResponse.Step(NextStep(Phase.AdvertiserVideoSurvey)),
            VideoResponse.Step(NextStep(Phase.Ready))
          )
        ),
        agreement(
          "MarkVideoWatchedRequest",
          List(
            MarkVideoWatchedRequest(30, completed = true),
            MarkVideoWatchedRequest(0, completed = false))),
        agreement("VideoWatchedResponse", List(VideoWatchedResponse("advertiser_video_survey"))),
        // The survey tree, where the format is a column as well as a response: these same bytes go
        // into `question_rules.rule_config` and `answers.answer_value`, so this also checks that
        // what the MCP backoffice writes is what the browser can read.
        agreement(
          "TextRule",
          List(
            TextRule.MinLength(3),
            TextRule.MaxLength(140),
            TextRule.Pattern("^[A-Z].*"),
            TextRule.Email,
            TextRule.Url)),
        agreement(
          "SelectionRule",
          List(SelectionRule.MinSelections(1), SelectionRule.MaxSelections(3))),
        agreement("RangeRule[Int]", List(RangeRule.Min(1), RangeRule.Max(5))),
        agreement(
          "RangeRule[BigDecimal]",
          List(RangeRule.Min(BigDecimal("0.5")), RangeRule.Max(BigDecimal("99.95")))),
        agreement(
          "RangeRule[LocalDate]",
          List(
            RangeRule.Min(LocalDate.parse("2020-01-01")),
            RangeRule.Max(LocalDate.parse("2030-12-31")))),
        agreement("CommonRule", CommonRule.values.toList),
        agreement("IdentificationSurveyType", IdentificationSurveyType.values.toList),
        agreement("LocalizedText", List(LocalizedText("¿Cuál es tu correo?", "es"))),
        agreement(
          "QuestionType",
          List(
            QuestionType.Radio(List(option)),
            QuestionType.Radio(Nil),
            QuestionType.Checkbox(List(option), Nil),
            QuestionType.Checkbox(List(option), List(SelectionRule.MinSelections(1))),
            QuestionType.Select(List(option)),
            QuestionType.Input(Nil),
            QuestionType.Input(List(TextRule.Email)),
            QuestionType.Rating(List(RangeRule.Min(1), RangeRule.Max(5))),
            QuestionType.Numeric(List(RangeRule.Max(BigDecimal("10")))),
            QuestionType.Date(List(RangeRule.Min(LocalDate.parse("2020-01-01"))))
          )
        ),
        agreement(
          "AnswerValue",
          List(
            AnswerValue.SingleChoice(anOptionId),
            AnswerValue.MultipleChoice(Set(anOptionId)),
            AnswerValue.Text("a written answer"),
            AnswerValue.Rating(4.5f),
            AnswerValue.Numeric(BigDecimal("12.75")),
            AnswerValue.DateValue(LocalDate.parse("2026-10-18"))
          )
        ),
        agreement("QuestionToAnswer", List(aQuestion, aRadioQuestion, anOptionlessQuestion)),
        agreement(
          "SurveyResponse",
          List(
            SurveyResponse.Survey(
              NextIdentificationSurvey(
                survey.Id.unsafe("11111111-1111-1111-1111-111111111111"),
                IdentificationSurveyType.Email,
                aQuestion)),
            SurveyResponse.Survey(
              NextIdentificationSurvey(
                survey.Id.unsafe("11111111-1111-1111-1111-111111111111"),
                IdentificationSurveyType.Profiling,
                aRadioQuestion)),
            SurveyResponse.Step(NextStep(Phase.AdvertiserVideo))
          )
        )
      )
end WireContractSuite
