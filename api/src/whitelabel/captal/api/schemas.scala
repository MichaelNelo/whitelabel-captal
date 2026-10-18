package whitelabel.captal.api

import sttp.tapir.Schema
import whitelabel.captal.core.application.commands.{
  NextAdvertiserSurvey,
  NextIdentificationSurvey,
  NextVideo
}
import whitelabel.captal.core.application.{IdentificationSurveyType, NextStep, Phase}
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.messages.*

/** Every tapir `Schema` this server's endpoint descriptions need.
  *
  * In `api` because that is the only module with tapir in it now. They used to live in a shared
  * `endpoints` module beside the request and response types, which put tapir on the browser's
  * compile classpath for declarations nothing in the browser reads.
  *
  * `Schema.string` and `Schema.anyObject` rather than derived wherever the JSON shape is written by
  * hand: a derived schema would describe a structure the codec does not produce.
  */
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

  // AdvertiserId
  given Schema[survey.AdvertiserId] = Schema.string

  // NextIdentificationSurvey, NextVideo, NextAdvertiserSurvey and NextStep
  given Schema[NextIdentificationSurvey] = Schema.derived
  given Schema[NextVideo] = Schema.derived
  given Schema[NextAdvertiserSurvey] = Schema.derived
  given Schema[NextStep] = Schema.derived
  given Schema[SurveyResponse] = Schema.anyObject

  // Moved here out of the DTO companions, and that is what makes those portable.
  //
  // A `given Schema[X]` sitting in `object X` is found by implicit search with no import, which is
  // convenient and is why they ended up there. It also means every DTO the client shares carries a
  // tapir dependency for the server's benefit — and `endpoints-js` compiles all of these, so the
  // browser build pulls in tapir to satisfy declarations nothing in it uses. Here they are one
  // import away for the five files that need them, and absent from everywhere else.
  given Schema[ApiError] = Schema.string
  given Schema[StatusResponse] = Schema.derived
  given Schema[AnswerRequest] = Schema.derived
  given Schema[VideoResponse] = Schema.anyObject
  given Schema[MarkVideoWatchedRequest] = Schema.derived
  given Schema[VideoWatchedResponse] = Schema.derived
  given Schema[AdvertiserSurveyResponse] = Schema.anyObject

  // `SetLocaleRequest` and the ten of the translation bundle were here. They went with
  // `LocaleRoutes`, which was the only thing that asked for them — this object shrinks as routes
  // convert, and empties when the last one does.
end schemas
