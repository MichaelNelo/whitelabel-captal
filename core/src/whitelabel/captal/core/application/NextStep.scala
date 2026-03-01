package whitelabel.captal.core.application

import io.circe.{Decoder, Encoder}

// DTO que indica el siguiente paso usando Phase existente
final case class NextStep(phase: Phase)

object NextStep:
  given Encoder[NextStep] = Encoder.AsObject.derived
  given Decoder[NextStep] = Decoder.derived
