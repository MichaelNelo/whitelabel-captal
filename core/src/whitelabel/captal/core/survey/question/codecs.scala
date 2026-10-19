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

  /** Browser-side codec for the `question_rules.rule_config` shape. `platform.surveySchemas` is the
    * authoritative definition of that format and must stay in step with these derivations.
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

  // One pair per element type: `RangeRule[A]` is generic, these instances are not. The
  // `targetName`s are required — three `given`s for one erased type collide on the synthetic name.
  @targetName("decoderRangeRuleInt")
  given Decoder[RangeRule[Int]] = Decoder.derived
  @targetName("encoderRangeRuleInt")
  given Encoder[RangeRule[Int]] = Encoder.AsObject.derived

  @targetName("decoderRangeRuleBigDecimal")
  given Decoder[RangeRule[BigDecimal]] = Decoder.derived
  @targetName("encoderRangeRuleBigDecimal")
  given Encoder[RangeRule[BigDecimal]] = Encoder.AsObject.derived

  // The only circe instances for `LocalDate` in this codebase. Decoding goes through
  // `IsoDate.parse`, not `LocalDate.parse`.
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

  // Response shape only; `SurveyRepositoryQuill.parseQuestionType` builds `QuestionType` from the
  // `questions.question_type` column and the rule rows, not from this JSON.
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

  // The one type here that travels browser -> server: the browser encodes, the server decodes. The
  // same bytes are persisted to `answers.answer_value`.
  given Encoder[AnswerValue] = Encoder.AsObject.derived
  given Decoder[AnswerValue] = Decoder.derived
end codecs
