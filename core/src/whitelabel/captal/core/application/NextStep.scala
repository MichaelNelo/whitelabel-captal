package whitelabel.captal.core.application

import io.circe.{Decoder, Encoder}

// The DTO naming the next step, in terms of the existing Phase.
final case class NextStep(phase: Phase)

object NextStep:
  given Encoder[NextStep] = Encoder.AsObject.derived
  given Decoder[NextStep] = Decoder.derived
