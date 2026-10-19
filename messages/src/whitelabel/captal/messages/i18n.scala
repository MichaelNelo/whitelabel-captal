package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.i18n.I18n

/** Circe codecs for the `I18n` translation bundle, which both sides of the wire need. The server's
  * `Schema[I18n]` is in `api.wire`.
  */
object i18n:
  // Circe codecs for nested types
  given CirceEncoder[I18n.Welcome.Steps] = deriveEncoder
  given CirceDecoder[I18n.Welcome.Steps] = deriveDecoder
  given CirceEncoder[I18n.Welcome.Button] = deriveEncoder
  given CirceDecoder[I18n.Welcome.Button] = deriveDecoder
  given CirceEncoder[I18n.Welcome.Authorized] = deriveEncoder
  given CirceDecoder[I18n.Welcome.Authorized] = deriveDecoder
  given CirceEncoder[I18n.Welcome] = deriveEncoder
  given CirceDecoder[I18n.Welcome] = deriveDecoder
  given CirceEncoder[I18n.Loading] = deriveEncoder
  given CirceDecoder[I18n.Loading] = deriveDecoder
  given CirceEncoder[I18n.Error] = deriveEncoder
  given CirceDecoder[I18n.Error] = deriveDecoder
  given CirceEncoder[I18n.Question] = deriveEncoder
  given CirceDecoder[I18n.Question] = deriveDecoder
  given CirceEncoder[I18n.Ready] = deriveEncoder
  given CirceDecoder[I18n.Ready] = deriveDecoder
  given CirceEncoder[I18n.Video] = deriveEncoder
  given CirceDecoder[I18n.Video] = deriveDecoder
  given CirceEncoder[I18n] = deriveEncoder
  given CirceDecoder[I18n] = deriveDecoder
end i18n
