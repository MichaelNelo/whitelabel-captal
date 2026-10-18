package whitelabel.captal.messages

import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.core.survey.question.codecs.given

final case class AnswerRequest(answer: AnswerValue)

object AnswerRequest:
  given Encoder[AnswerRequest] = Encoder.AsObject.derived
  given Decoder[AnswerRequest] = Decoder.derived
