package whitelabel.captal.api

import whitelabel.captal.core.application.commands.{NextAdvertiserSurvey, NextVideo}
import whitelabel.captal.core.application.{NextStep, Phase}
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.messages.{
  AdvertiserSurveyResponse,
  AnswerRequest,
  ApiError,
  MarkVideoWatchedRequest,
  SetLocaleRequest,
  StatusResponse,
  SurveyResponse,
  VideoResponse,
  VideoWatchedResponse
}
import whitelabel.captal.platform.surveySchemas.given
import zio.schema.{DeriveSchema, Schema}

/** The server's half of the wire format: a `Schema` for every type a route sends or receives. A
  * zio-schema `Schema` *is* the codec, so every entry below is load-bearing.
  *
  * JVM-only on purpose: these must not reach the Scala.js classpath, where the browser decodes the
  * same types with circe.
  */
object wire:

  /** `Phase` goes over the wire as one of seven plain strings, which the browser reads with
    * `Decoder.decodeString.map(fromDbString)`. Hand-written: a derived enum schema would write the
    * variant name in an object instead. `fromDbString` is total — an unknown string is `Welcome`.
    */
  given Schema[Phase] = Schema[String].transform(Phase.fromDbString, Phase.toDbString)

  /** Derived, unlike `Phase`: both sides of `ApiError` are derived, so the variant name goes in an
    * object. `WireContractSuite` holds the two sides together.
    */
  given Schema[ApiError] = DeriveSchema.gen[ApiError]

  given Schema[StatusResponse] = DeriveSchema.gen[StatusResponse]

  given Schema[SetLocaleRequest] = DeriveSchema.gen[SetLocaleRequest]

  /** The translation bundle — ten nested case classes of nothing but `String` — derived whole:
    * `DeriveSchema.gen` reaches the nested types on its own.
    */
  given Schema[I18n] = DeriveSchema.gen[I18n]

  // ─── Video ────────────────────────────────────────────────────────────────

  // `NextStep` carries a `Phase`, so it is derived after the instance above and not before.
  given Schema[NextStep] = DeriveSchema.gen[NextStep]
  given Schema[NextVideo] = DeriveSchema.gen[NextVideo]
  given Schema[VideoResponse] = DeriveSchema.gen[VideoResponse]
  given Schema[MarkVideoWatchedRequest] = DeriveSchema.gen[MarkVideoWatchedRequest]
  given Schema[VideoWatchedResponse] = DeriveSchema.gen[VideoWatchedResponse]

  // ─── Survey ───────────────────────────────────────────────────────────────

  // The question tree itself is in `platform.surveySchemas`: `question_rules.rule_config` and
  // `answers.answer_value` hold the same bytes this serves, so one description covers the column,
  // the response and the MCP backoffice that writes them. What is left here is the wrappers.
  given Schema[SurveyResponse] = DeriveSchema.gen[SurveyResponse]
  given Schema[AnswerRequest] = DeriveSchema.gen[AnswerRequest]
  given Schema[NextAdvertiserSurvey] = DeriveSchema.gen[NextAdvertiserSurvey]
  given Schema[AdvertiserSurveyResponse] = DeriveSchema.gen[AdvertiserSurveyResponse]

end wire
