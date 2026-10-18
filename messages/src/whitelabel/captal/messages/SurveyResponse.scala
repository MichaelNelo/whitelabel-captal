package whitelabel.captal.messages

import io.circe.syntax.*
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
  * The discriminator is written and read by hand rather than derived, because the two cases are
  * flattened into the same object rather than nested under a key — the client reads `type` and then
  * the fields beside it.
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

  given CirceEncoder[SurveyResponse] = CirceEncoder.instance:
    case Survey(data) =>
      data.asJson.deepMerge(io.circe.Json.obj("type" -> "survey".asJson))
    case Step(data) =>
      data.asJson.deepMerge(io.circe.Json.obj("type" -> "step".asJson))

  given CirceDecoder[SurveyResponse] = CirceDecoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "survey" =>
          cursor.as[NextIdentificationSurvey].map(Survey.apply)
        case "step" =>
          cursor.as[NextStep].map(Step.apply)
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown type: $other", cursor.history))
end SurveyResponse
