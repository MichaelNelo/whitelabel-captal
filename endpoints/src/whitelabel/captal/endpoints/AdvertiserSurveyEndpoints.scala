package whitelabel.captal.endpoints

import io.circe.syntax.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import sttp.tapir.Schema
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextAdvertiserSurvey

// Response type for advertiser survey endpoints - discriminated union
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

  given CirceEncoder[AdvertiserSurveyResponse] = CirceEncoder.instance:
    case Survey(data) =>
      data.asJson.deepMerge(io.circe.Json.obj("type" -> "survey".asJson))
    case Step(data) =>
      data.asJson.deepMerge(io.circe.Json.obj("type" -> "step".asJson))

  given CirceDecoder[AdvertiserSurveyResponse] = CirceDecoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "survey" =>
          cursor.as[NextAdvertiserSurvey].map(Survey.apply)
        case "step" =>
          cursor.as[NextStep].map(Step.apply)
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown type: $other", cursor.history))

  given Schema[AdvertiserSurveyResponse] = Schema.anyObject
end AdvertiserSurveyResponse
