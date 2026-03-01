package whitelabel.captal.endpoints

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema

final case class SetLocaleRequest(locale: String)

object SetLocaleRequest:
  given Encoder[SetLocaleRequest] = deriveEncoder
  given Decoder[SetLocaleRequest] = deriveDecoder
  given Schema[SetLocaleRequest] = Schema.derived
