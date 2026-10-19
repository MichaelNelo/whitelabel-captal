package whitelabel.captal.core.application

import io.circe.{Decoder, Encoder}

// DTO naming the next step as a Phase.
final case class NextStep(phase: Phase)

object NextStep:
  given Encoder[NextStep] = Encoder.AsObject.derived
  given Decoder[NextStep] = Decoder.derived
