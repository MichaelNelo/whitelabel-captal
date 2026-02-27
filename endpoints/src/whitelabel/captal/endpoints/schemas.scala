package whitelabel.captal.endpoints

import sttp.tapir.Schema
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.*

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

  // NextIdentificationSurvey
  given Schema[NextIdentificationSurvey] = Schema.derived
