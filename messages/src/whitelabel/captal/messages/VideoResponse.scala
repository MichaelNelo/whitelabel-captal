package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextVideo

/** The three types of one video exchange: ask for the next video, report it watched, get told the
  * phase that follows. Their server-side `Schema` instances are in `api.wire`.
  */
enum VideoResponse:
  case Video(data: NextVideo)
  case Step(data: NextStep)

object VideoResponse:
  def from(value: NextVideo | NextStep): VideoResponse =
    value match
      case v: NextVideo =>
        Video(v)
      case n: NextStep =>
        Step(n)

  /** Derived both directions; the wire shape is the derivations' — `{"Video":{"data":{…}}}`, the
    * payload nested under the case name. A zio-schema `Schema` cannot express a flattened
    * discriminator, so do not hand-write these. `WireContractSuite` holds both sides to one shape.
    */
  given CirceEncoder[VideoResponse] = CirceEncoder.AsObject.derived
  given CirceDecoder[VideoResponse] = CirceDecoder.derived
end VideoResponse

final case class MarkVideoWatchedRequest(durationWatched: Int, completed: Boolean)

object MarkVideoWatchedRequest:
  given CirceEncoder[MarkVideoWatchedRequest] = deriveEncoder
  given CirceDecoder[MarkVideoWatchedRequest] = deriveDecoder

final case class VideoWatchedResponse(nextPhase: String)

object VideoWatchedResponse:
  given CirceEncoder[VideoWatchedResponse] = deriveEncoder
  given CirceDecoder[VideoWatchedResponse] = deriveDecoder
