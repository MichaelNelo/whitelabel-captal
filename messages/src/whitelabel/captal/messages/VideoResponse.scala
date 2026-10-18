package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextVideo

/** The three types the video endpoints exchange, split out of `VideoEndpoints.scala`.
  *
  * That file genuinely held both -- the endpoint definitions and these -- which is why the client
  * compiled against tapir: `endpoints-js` shares every source in this module, so a browser build
  * pulled in the tapir dependency to typecheck declarations it never reads. Their `Schema`
  * instances are in `schemas` now and these three are free of it.
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

  given CirceEncoder[VideoResponse] = CirceEncoder.instance:
    case Video(data) =>
      data.asJson.deepMerge(io.circe.Json.obj("type" -> "video".asJson))
    case Step(data) =>
      data.asJson.deepMerge(io.circe.Json.obj("type" -> "step".asJson))

  given CirceDecoder[VideoResponse] = CirceDecoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "video" =>
          cursor.as[NextVideo].map(Video.apply)
        case "step" =>
          cursor.as[NextStep].map(Step.apply)
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown type: $other", cursor.history))
end VideoResponse

final case class MarkVideoWatchedRequest(durationWatched: Int, completed: Boolean)

object MarkVideoWatchedRequest:
  given CirceEncoder[MarkVideoWatchedRequest] = deriveEncoder
  given CirceDecoder[MarkVideoWatchedRequest] = deriveDecoder

final case class VideoWatchedResponse(nextPhase: String)

object VideoWatchedResponse:
  given CirceEncoder[VideoWatchedResponse] = deriveEncoder
  given CirceDecoder[VideoWatchedResponse] = deriveDecoder
