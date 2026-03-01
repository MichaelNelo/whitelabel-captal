package whitelabel.captal.endpoints

import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import sttp.tapir.Schema
import whitelabel.captal.core.application.{IdentificationSurveyType, NextStep, Phase}
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.*

// Response type for survey endpoints - discriminated union
enum SurveyResponse:
  case Survey(data: NextIdentificationSurvey)
  case Step(data: NextStep)

object SurveyResponse:
  def from(value: NextIdentificationSurvey | NextStep): SurveyResponse =
    value match
      case s: NextIdentificationSurvey => Survey(s)
      case n: NextStep                 => Step(n)

  given CirceEncoder[SurveyResponse] = CirceEncoder.instance:
    case Survey(data) => data.asJson.deepMerge(io.circe.Json.obj("type" -> "survey".asJson))
    case Step(data)   => data.asJson.deepMerge(io.circe.Json.obj("type" -> "step".asJson))

  given CirceDecoder[SurveyResponse] = CirceDecoder.instance: cursor =>
    cursor.get[String]("type").flatMap:
      case "survey" => cursor.as[NextIdentificationSurvey].map(Survey.apply)
      case "step"   => cursor.as[NextStep].map(Step.apply)
      case other    => Left(io.circe.DecodingFailure(s"Unknown type: $other", cursor.history))

object schemas:
  // Phase and IdentificationSurveyType
  given Schema[Phase] = Schema.string
  given Schema[IdentificationSurveyType] = Schema.string

  // IDs (opaque types serialized as strings)
  given surveyIdSchema: Schema[survey.Id] = Schema.string
  given questionIdSchema: Schema[survey.question.Id] = Schema.string
  given optionIdSchema: Schema[OptionId] = Schema.string

  // CommonRule
  given Schema[CommonRule] = Schema.string

  // LocalizedText and QuestionOption
  given Schema[LocalizedText] = Schema.derived
  given Schema[QuestionOption] = Schema.derived

  // Rules - use anyObject for complex enums
  given Schema[SelectionRule] = Schema.anyObject
  given Schema[TextRule] = Schema.anyObject
  given schemaRangeRuleInt: Schema[RangeRule[Int]] = Schema.anyObject
  given schemaRangeRuleBigDecimal: Schema[RangeRule[BigDecimal]] = Schema.anyObject
  given schemaRangeRuleLocalDate: Schema[RangeRule[java.time.LocalDate]] = Schema.anyObject

  // QuestionType and QuestionToAnswer
  given Schema[QuestionType] = Schema.anyObject
  given Schema[QuestionToAnswer] = Schema.derived

  // AnswerValue
  given Schema[AnswerValue] = Schema.anyObject

  // NextIdentificationSurvey and NextStep
  given Schema[NextIdentificationSurvey] = Schema.derived
  given Schema[NextStep] = Schema.derived
  given Schema[SurveyResponse] = Schema.anyObject

object i18n:
  // Circe codecs for nested types
  given CirceEncoder[I18n.Welcome.Steps] = deriveEncoder
  given CirceDecoder[I18n.Welcome.Steps] = deriveDecoder
  given CirceEncoder[I18n.Welcome.Button] = deriveEncoder
  given CirceDecoder[I18n.Welcome.Button] = deriveDecoder
  given CirceEncoder[I18n.Welcome] = deriveEncoder
  given CirceDecoder[I18n.Welcome] = deriveDecoder
  given CirceEncoder[I18n.Loading] = deriveEncoder
  given CirceDecoder[I18n.Loading] = deriveDecoder
  given CirceEncoder[I18n.Error] = deriveEncoder
  given CirceDecoder[I18n.Error] = deriveDecoder
  given CirceEncoder[I18n.Question] = deriveEncoder
  given CirceDecoder[I18n.Question] = deriveDecoder
  given CirceEncoder[I18n] = deriveEncoder
  given CirceDecoder[I18n] = deriveDecoder

  // Tapir schemas for I18n
  given Schema[I18n.Welcome.Steps] = Schema.derived
  given Schema[I18n.Welcome.Button] = Schema.derived
  given Schema[I18n.Welcome] = Schema.derived
  given Schema[I18n.Loading] = Schema.derived
  given Schema[I18n.Error] = Schema.derived
  given Schema[I18n.Question] = Schema.derived
  given Schema[I18n] = Schema.derived
