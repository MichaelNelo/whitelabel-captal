package whitelabel.captal.messages

import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextIdentificationSurvey

/** What the identification-survey endpoints answer with: the next question or the phase that
  * follows.
  *
  * Split out of `schemas.scala`, which held it beside the tapir `Schema` instances. The client uses
  * this type in thirteen places and has no use for tapir, so sharing a file with it meant the
  * browser build compiled against tapir to satisfy declarations it never reads. The `Schema` for
  * this type stayed behind in `schemas`.
  *
  * The discriminator was written and read by hand, flattening each case's payload into the same
  * object rather than nesting it under a key. That is what a zio-schema `Schema` cannot describe,
  * so both sides are derived now — see `VideoResponse`, which made the same move for the same
  * reason.
  */
enum SurveyResponse:
  case Survey(data: NextIdentificationSurvey)
  case Step(data: NextStep)

object SurveyResponse:
  def from(value: NextIdentificationSurvey | NextStep): SurveyResponse =
    value match
      case s: NextIdentificationSurvey =>
        Survey(s)
      case n: NextStep =>
        Step(n)

  given CirceEncoder[SurveyResponse] = CirceEncoder.AsObject.derived
  given CirceDecoder[SurveyResponse] = CirceDecoder.derived
end SurveyResponse
