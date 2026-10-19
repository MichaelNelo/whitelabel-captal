package whitelabel.captal.messages

import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextAdvertiserSurvey

/** What the advertiser-survey endpoints answer with: the next question or the phase that follows.
  *
  * Its server-side `Schema` lives in `api.wire`, keeping this type free of the server's codec
  * library.
  */
enum AdvertiserSurveyResponse:
  case Survey(data: NextAdvertiserSurvey)
  case Step(data: NextStep)

object AdvertiserSurveyResponse:
  def from(value: NextAdvertiserSurvey | NextStep): AdvertiserSurveyResponse =
    value match
      case s: NextAdvertiserSurvey =>
        Survey(s)
      case n: NextStep =>
        Step(n)

  given CirceEncoder[AdvertiserSurveyResponse] = CirceEncoder.AsObject.derived
  given CirceDecoder[AdvertiserSurveyResponse] = CirceDecoder.derived
end AdvertiserSurveyResponse
