package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

/** The terms of service in force, in the session's locale: what the welcome's dialog shows.
  *
  * @param version
  *   which version this is, for the guest to see; accepting it does not send it back, since the
  *   server records the version in force when the acceptance arrives.
  */
final case class TermsResponse(version: Int, text: String)

object TermsResponse:
  given Encoder[TermsResponse] = deriveEncoder
  given Decoder[TermsResponse] = deriveDecoder
