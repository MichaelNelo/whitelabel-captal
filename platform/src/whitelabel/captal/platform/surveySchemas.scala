package whitelabel.captal.platform

import java.time.LocalDate

import whitelabel.captal.core.application.IdentificationSurveyType
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.*
import zio.schema.codec.JsonCodec
import zio.schema.{DeriveSchema, Schema}

/** The one zio-schema description of the survey question tree: both `question_rules.rule_config`
  * and the survey response are written from it. In `platform`, the single module `api` and `mcp`
  * share.
  */
object surveySchemas:

  // ─── Opaque ids ───────────────────────────────────────────────────────────

  // `DeriveSchema.gen` cannot see through an opaque type, so these say what the string is; the
  // transform makes a malformed id decode to an error instead of throwing. They must be named:
  // opaque types erase to `String`, so anonymous `given`s for several `Id`s would collide.
  given surveyIdSchema: Schema[survey.Id] = Schema[String].transformOrFail(
    text => survey.Id.fromString(text).toRight(s"Not a survey id: $text"),
    id => Right(id.asString))

  given questionIdSchema: Schema[survey.question.Id] = Schema[String].transformOrFail(
    text => survey.question.Id.fromString(text).toRight(s"Not a question id: $text"),
    id => Right(id.asString))

  given optionIdSchema: Schema[OptionId] = Schema[String].transformOrFail(
    text => OptionId.fromString(text).toRight(s"Not an option id: $text"),
    id => Right(id.asString))

  given advertiserIdSchema: Schema[survey.AdvertiserId] = Schema[String].transformOrFail(
    text => survey.AdvertiserId.fromString(text).toRight(s"Not an advertiser id: $text"),
    id => Right(id.asString))

  // ─── Leaves ───────────────────────────────────────────────────────────────

  given Schema[LocalizedText] = DeriveSchema.gen[LocalizedText]
  given Schema[QuestionOption] = DeriveSchema.gen[QuestionOption]

  /** The two enums that stay plain strings on both sides of both boundaries:
    * `IdentificationSurveyType` is a Quill `MappedEncoding` over `surveys.category`, `CommonRule`
    * is how `questions.is_required` is read, and both also travel to the browser inside
    * `NextIdentificationSurvey`. These match the circe instances rather than replacing them.
    */
  given Schema[CommonRule] = Schema[String].transformOrFail(
    {
      case "required" =>
        Right(CommonRule.Required)
      case other =>
        Left(s"Unknown common rule: $other")
    },
    { case CommonRule.Required =>
      Right("required")
    })

  given Schema[IdentificationSurveyType] = Schema[String].transform(
    IdentificationSurveyType.fromDbString,
    IdentificationSurveyType.toDbString)

  // ─── Rules ────────────────────────────────────────────────────────────────

  given Schema[SelectionRule] = DeriveSchema.gen[SelectionRule]
  given Schema[TextRule] = DeriveSchema.gen[TextRule]

  // One per element type: `RangeRule[A]` is generic and a `Schema` is not, and each of the three
  // is the rule list of exactly one `QuestionType` case. Named, since the type parameter does not
  // reach the synthetic given name.
  given rangeRuleIntSchema: Schema[RangeRule[Int]] = DeriveSchema.gen[RangeRule[Int]]
  given rangeRuleBigDecimalSchema
      : Schema[RangeRule[BigDecimal]] = DeriveSchema.gen[RangeRule[BigDecimal]]
  given rangeRuleLocalDateSchema
      : Schema[RangeRule[LocalDate]] = DeriveSchema.gen[RangeRule[LocalDate]]

  // ─── The tree ─────────────────────────────────────────────────────────────

  given Schema[QuestionType] = DeriveSchema.gen[QuestionType]
  given Schema[QuestionToAnswer] = DeriveSchema.gen[QuestionToAnswer]
  given Schema[AnswerValue] = DeriveSchema.gen[AnswerValue]
  given Schema[NextIdentificationSurvey] = DeriveSchema.gen[NextIdentificationSurvey]

  // ─── What the database column holds ───────────────────────────────────────

  /** Render a value as the JSON a `TEXT` column stores. */
  def encode[A: Schema](value: A): String =
    JsonCodec.jsonEncoder(summon[Schema[A]]).encodeJson(value, None).toString

  /** Read a column back, with the reason on the `Left` when it will not read. */
  def decode[A: Schema](json: String): Either[String, A] = JsonCodec
    .jsonDecoder(summon[Schema[A]])
    .decodeJson(json)

end surveySchemas
