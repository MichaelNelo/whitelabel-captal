package whitelabel.captal.endpoints

import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.endpoints.schemas.given

final case class AnswerRequest(answer: AnswerValue)

object AnswerRequest:
  given Encoder[AnswerRequest] = Encoder.AsObject.derived
  given Decoder[AnswerRequest] = Decoder.derived
  given Schema[AnswerRequest] = Schema.derived
