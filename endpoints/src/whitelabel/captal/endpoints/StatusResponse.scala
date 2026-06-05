package whitelabel.captal.endpoints

import java.time.Instant

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema
import whitelabel.captal.core.application.Phase
import whitelabel.captal.endpoints.schemas.given

final case class StatusResponse(
    phase: Phase,
    locale: String,
    accessExpiresAt: Option[Instant] = None,
    redirectUrl: Option[String] = None)

object StatusResponse:
  given Encoder[StatusResponse] = deriveEncoder
  given Decoder[StatusResponse] = deriveDecoder
  given Schema[StatusResponse] = Schema.derived
