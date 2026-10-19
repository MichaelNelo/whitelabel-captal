package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextVideo

/** The three types the video endpoints exchange, split out of `VideoEndpoints.scala`.
  *
  * That file genuinely held both -- the endpoint definitions and these -- which is why the client
  * compiled against tapir: `endpoints-js` shares every source in this module, so a browser build
  * pulled in the tapir dependency to typecheck declarations it never reads. Their `Schema`
  * instances are in `api.wire` now and these three are free of them.
  *
  * Together in one file because they are one exchange: ask for the next video, report it watched,
  * get told the phase that follows.
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

  /** Derived, where this was a hand-written pair flattening the payload under a `type` field.
    *
    * The flattening is what had to go: zio-http's `Endpoint` serialises with a zio-schema `Schema`,
    * and a `Schema` that merges a nested object's fields into its parent's and puts a discriminator
    * beside them is not something the library can express. So both sides are derived and the shape
    * is the derivations' — `{"Video":{"data":{…}}}` rather than `{…,"type":"video"}`.
    *
    * Nothing read those bytes directly. The browser and the suites both decode to this enum and
    * match on it, so the change is confined to the two derivations, with `WireContractSuite`
    * holding them to one shape. Compare `ApiError`, whose two hand-written matches had silently
    * disagreed for the twenty-two variants its decoder never covered: the risk in writing a codec
    * twice is structural, and deriving is what removes it.
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
