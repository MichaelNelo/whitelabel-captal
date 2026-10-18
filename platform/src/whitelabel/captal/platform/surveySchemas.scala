package whitelabel.captal.platform

import java.time.LocalDate

import whitelabel.captal.core.application.IdentificationSurveyType
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.*
import zio.schema.codec.JsonCodec
import zio.schema.{DeriveSchema, Schema}

/** One zio-schema description of the survey question tree, for the three places that write it.
  *
  * The tree had three independent renderings of one format and no shared definition of it. The API
  * served it through circe; `SurveyRepositoryQuill` read `question_rules.rule_config` back through
  * circe decoders; and the MCP backoffice wrote that same column by building the JSON with string
  * interpolation — `s"""{"type":"$kind","value":$v}"""` — which agreed with the other two by
  * nobody's enforcement. The reader dropped what it could not parse with `.toOption`, so a
  * disagreement did not fail: the rule simply stopped being enforced, silently, for every question
  * that carried it.
  *
  * So this is the definition, and the column and the response are both written from it. In
  * `platform` because that is the one module `api` and `mcp` share — `core` would put a
  * serialisation library on the Scala.js classpath, and the browser reads this tree with circe.
  *
  * Derived rather than annotated to reproduce the old shape. The old shape was reproducible — a
  * `@discriminatorName` and a `@caseName`/`@fieldName` per case would have matched it key for key —
  * but there is nothing to stay compatible with: the format's only persisted instances are rows
  * this same code writes, and matching it by hand would mean re-stating, in annotations, the
  * mapping whose three uncoordinated copies are the problem being removed.
  */
object surveySchemas:

  // ─── Opaque ids ───────────────────────────────────────────────────────────

  // `DeriveSchema.gen` cannot see through an opaque type — it reports "Deriving schema for
  // survey.Id is not supported" — so these say what the string is. A transform rather than a
  // primitive, so a malformed id in a request body decodes to an error instead of throwing.
  // Named, because an opaque type erases to its underlying one and three anonymous `given`s for
  // three `Id`s collide on the synthetic name the compiler would give them.
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

  /** The two enums that stay plain strings, because a column already holds them that way.
    *
    * `IdentificationSurveyType` is a Quill `MappedEncoding` over `surveys.category` and
    * `CommonRule` is how `questions.is_required` is read — neither is JSON in the database, and
    * both also travel to the browser inside `NextIdentificationSurvey`. So the string is the format
    * on both sides of both boundaries, and these match the circe instances rather than replacing
    * them. The same arrangement `Phase` has in `api/wire.scala`, for the same reason.
    *
    * `HierarchyLevel` is not here: it is read from `questions.hierarchy_level` by quoting the
    * column and handing it to a circe string decoder, and it reaches no response type — nothing in
    * `client`, `messages` or `api` mentions it. A `Schema` for it would describe a boundary it
    * never crosses.
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

  // One per element type, because `RangeRule[A]` is generic and a `Schema` is not: each of the
  // three is the rule list of exactly one `QuestionType` case. Named for the same reason the ids
  // are — the type parameter does not reach the synthetic name.
  given rangeRuleIntSchema: Schema[RangeRule[Int]] = DeriveSchema.gen[RangeRule[Int]]
  given rangeRuleBigDecimalSchema: Schema[RangeRule[BigDecimal]] = DeriveSchema
    .gen[RangeRule[BigDecimal]]
  given rangeRuleLocalDateSchema: Schema[RangeRule[LocalDate]] = DeriveSchema
    .gen[RangeRule[LocalDate]]

  // ─── The tree ─────────────────────────────────────────────────────────────

  given Schema[QuestionType] = DeriveSchema.gen[QuestionType]
  given Schema[QuestionToAnswer] = DeriveSchema.gen[QuestionToAnswer]
  given Schema[AnswerValue] = DeriveSchema.gen[AnswerValue]
  given Schema[NextIdentificationSurvey] = DeriveSchema.gen[NextIdentificationSurvey]

  // ─── What the database column holds ───────────────────────────────────────

  /** Render a value as the JSON a `TEXT` column stores.
    *
    * Here rather than at each call site so the reader and the two writers cannot drift: the column
    * is written by `mcp` and read by `SurveyRepositoryQuill`, in different modules, and this is the
    * only description either of them uses.
    */
  def encode[A: Schema](value: A): String = JsonCodec
    .jsonEncoder(summon[Schema[A]])
    .encodeJson(value, None)
    .toString

  /** Read a column back, with the reason when it will not read.
    *
    * `Either` and not `Option`, which is the difference that matters: the caller this replaces
    * discarded the reason, so a rule whose JSON no longer matched the decoder became a rule that
    * was not enforced, with nothing written anywhere to say so.
    */
  def decode[A: Schema](json: String): Either[String, A] = JsonCodec
    .jsonDecoder(summon[Schema[A]])
    .decodeJson(json)

end surveySchemas
