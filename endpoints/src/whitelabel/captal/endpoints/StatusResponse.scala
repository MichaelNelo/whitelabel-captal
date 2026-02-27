package whitelabel.captal.endpoints

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import sttp.tapir.Schema
import whitelabel.captal.core.application.Phase
import whitelabel.captal.endpoints.schemas.given

final case class StatusResponse(phase: Phase, locale: String)

object StatusResponse:
  given Encoder[StatusResponse] = deriveEncoder
  given Decoder[StatusResponse] = deriveDecoder
  given Schema[StatusResponse] = Schema.derived
