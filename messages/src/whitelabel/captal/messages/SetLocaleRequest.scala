package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

final case class SetLocaleRequest(locale: String)

object SetLocaleRequest:
  given Encoder[SetLocaleRequest] = deriveEncoder
  given Decoder[SetLocaleRequest] = deriveDecoder
