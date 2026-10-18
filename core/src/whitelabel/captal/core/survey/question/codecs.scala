package whitelabel.captal.core.survey.question

import java.time.LocalDate

import scala.annotation.targetName

import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.survey

object codecs:
  // ─────────────────────────────────────────────────────────────────────────────
  // IDs
  // ─────────────────────────────────────────────────────────────────────────────

  given surveyIdEncoder: Encoder[survey.Id] = Encoder.encodeString.contramap(_.asString)
  given surveyIdDecoder: Decoder[survey.Id] = Decoder
    .decodeString
    .emap(s => survey.Id.fromString(s).toRight(s"Invalid survey id: $s"))

  given questionIdEncoder: Encoder[Id] = Encoder.encodeString.contramap(_.asString)
  given questionIdDecoder: Decoder[Id] = Decoder
    .decodeString
    .emap(s => Id.fromString(s).toRight(s"Invalid question id: $s"))

  given optionIdEncoder: Encoder[OptionId] = Encoder.encodeString.contramap(_.asString)
  given optionIdDecoder: Decoder[OptionId] = Decoder
    .decodeString
    .emap(s => OptionId.fromString(s).toRight(s"Invalid option id: $s"))

  given advertiserIdEncoder: Encoder[survey.AdvertiserId] = Encoder
    .encodeString
    .contramap(_.asString)
  given advertiserIdDecoder: Decoder[survey.AdvertiserId] = Decoder
    .decodeString
    .emap(s => survey.AdvertiserId.fromString(s).toRight(s"Invalid advertiser id: $s"))

  // ─────────────────────────────────────────────────────────────────────────────
  // Selection Rules
  // ─────────────────────────────────────────────────────────────────────────────

  /** Derived, where the rule and answer codecs below were all pairs of hand-written matches.
    *
    * These describe `question_rules.rule_config` and `answers.answer_value`, which are columns as
    * much as they are responses: the MCP backoffice writes the first, `SurveyRepositoryQuill` reads
    * it, and the API serves it. Three renderings of one format with no shared definition — and the
    * MCP's was string interpolation, agreeing with the other two by nobody's enforcement, while the
    * reader discarded what it could not parse with `.toOption`, so a disagreement did not fail: the
    * rule simply stopped being enforced.
    *
    * `platform.surveySchemas` is the definition now, and both the column and the response are
    * written from it. What stays here is the browser's half of the same shape, since the browser
    * reads this tree with circe — and `WireContractSuite` holds the two derivations to one format.
    */
  given Decoder[SelectionRule] = Decoder.derived
  given Encoder[SelectionRule] = Encoder.AsObject.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // Text Rules
  // ─────────────────────────────────────────────────────────────────────────────

  given Decoder[TextRule] = Decoder.derived
  given Encoder[TextRule] = Encoder.AsObject.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // Range Rules
  // ─────────────────────────────────────────────────────────────────────────────

  // One pair per element type, because `RangeRule[A]` is generic and these instances are not. The
  // `targetName`s stay: three `given`s for one erased type still collide on the synthetic name.
  @targetName("decoderRangeRuleInt")
  given Decoder[RangeRule[Int]] = Decoder.derived
  @targetName("encoderRangeRuleInt")
  given Encoder[RangeRule[Int]] = Encoder.AsObject.derived

  @targetName("decoderRangeRuleBigDecimal")
  given Decoder[RangeRule[BigDecimal]] = Decoder.derived
  @targetName("encoderRangeRuleBigDecimal")
  given Encoder[RangeRule[BigDecimal]] = Encoder.AsObject.derived

  // `LocalDate` has no circe instance of its own here, so these two carry one. `IsoDate.parse`
  // rather than `LocalDate.parse` for the reason its own file gives.
  private given Decoder[LocalDate] = Decoder.decodeString.map(IsoDate.parse)
  private given Encoder[LocalDate] = Encoder.encodeString.contramap(_.toString)

  @targetName("decoderRangeRuleLocalDate")
  given Decoder[RangeRule[LocalDate]] = Decoder.derived
  @targetName("encoderRangeRuleLocalDate")
  given Encoder[RangeRule[LocalDate]] = Encoder.AsObject.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // Common Rules
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[CommonRule] = Encoder
    .encodeString
    .contramap:
      case CommonRule.Required =>
        "required"

  given Decoder[CommonRule] = Decoder
    .decodeString
    .emap:
      case "required" =>
        Right(CommonRule.Required)
      case other =>
        Left(s"Unknown common rule: $other")

  // ─────────────────────────────────────────────────────────────────────────────
  // Hierarchy Level
  // ─────────────────────────────────────────────────────────────────────────────

  given Decoder[HierarchyLevel] = Decoder
    .decodeString
    .emap:
      case "state" =>
        Right(HierarchyLevel.State)
      case "city" =>
        Right(HierarchyLevel.City)
      case "municipality" =>
        Right(HierarchyLevel.Municipality)
      case "urbanization" =>
        Right(HierarchyLevel.Urbanization)
      case other =>
        Left(s"Unknown hierarchy level: $other")

  given Encoder[HierarchyLevel] = Encoder
    .encodeString
    .contramap:
      case HierarchyLevel.State =>
        "state"
      case HierarchyLevel.City =>
        "city"
      case HierarchyLevel.Municipality =>
        "municipality"
      case HierarchyLevel.Urbanization =>
        "urbanization"

  // ─────────────────────────────────────────────────────────────────────────────
  // LocalizedText & QuestionOption (derived)
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[LocalizedText] = Encoder.AsObject.derived
  given Decoder[LocalizedText] = Decoder.derived

  given Encoder[QuestionOption] = Encoder.AsObject.derived
  given Decoder[QuestionOption] = Decoder.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // QuestionType
  // ─────────────────────────────────────────────────────────────────────────────

  // `QuestionType`'s discriminator mirrored the `questions.question_type` column, but the JSON was
  // never the column: `SurveyRepositoryQuill.parseQuestionType` builds this from that column and
  // the rule rows by hand. So the shape here was only ever the response, and nothing persisted
  // depends on `Select` having been spelled `dropdown`.
  given Encoder[QuestionType] = Encoder.AsObject.derived
  given Decoder[QuestionType] = Decoder.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // QuestionToAnswer (derived)
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[QuestionToAnswer] = Encoder.AsObject.derived
  given Decoder[QuestionToAnswer] = Decoder.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // AnswerValue
  // ─────────────────────────────────────────────────────────────────────────────

  // The one type here that travels the other way: the browser encodes an answer and the server
  // decodes it. `answers.answer_value` holds the same bytes, written by
  // `AnswerPersistenceHandler` — so this shape is a column too, and `platform.surveySchemas` is
  // what writes it now.
  given Encoder[AnswerValue] = Encoder.AsObject.derived
  given Decoder[AnswerValue] = Decoder.derived
end codecs
