package whitelabel.captal.messages

import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextIdentificationSurvey

/** What the identification-survey endpoints answer with: the next question or the phase that
  * follows.
  *
  * Its server-side `Schema` lives in `api.wire`; both codecs below are derived, so the payload
  * nests under the case name rather than being flattened beside a discriminator.
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
