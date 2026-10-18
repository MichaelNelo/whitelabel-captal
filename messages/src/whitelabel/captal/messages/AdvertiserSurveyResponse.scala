package whitelabel.captal.messages

import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextAdvertiserSurvey

/** What the advertiser-survey endpoints answer with: the next question or the phase that follows.
  *
  * The file was called `AdvertiserSurveyEndpoints.scala` and held no endpoint, which turned out to
  * be honest about something larger: those endpoints are not declared in this module at all.
  * `api`'s `AdvertiserSurveyRoutes` builds them inline with its own `jsonBody`, so the shared
  * contract this module is meant to be is not how every route is actually defined.
  *
  * Renamed to what it contains, and the tapir `Schema` moved to `schemas` — which is what keeps
  * this type free of tapir for the client that reads it in nine places.
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

  // Derived, like the other two wrappers. See `VideoResponse` for why a flattened `type`
  // discriminator could not survive the move to a zio-schema `Schema`.
  given CirceEncoder[AdvertiserSurveyResponse] = CirceEncoder.AsObject.derived
  given CirceDecoder[AdvertiserSurveyResponse] = CirceDecoder.derived
end AdvertiserSurveyResponse
