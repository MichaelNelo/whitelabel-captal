package whitelabel.captal.endpoints

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}
import sttp.tapir.json.circe.*
import sttp.tapir.{Schema, *}
import whitelabel.captal.core.application.NextStep
import whitelabel.captal.core.application.commands.NextVideo
import whitelabel.captal.endpoints.ApiError.given

// Response type for video endpoints - discriminated union
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

  given Schema[VideoResponse] = Schema.anyObject
end VideoResponse

final case class MarkVideoWatchedRequest(
    durationWatched: Int,
    completed: Boolean)

object MarkVideoWatchedRequest:
  given CirceEncoder[MarkVideoWatchedRequest] = deriveEncoder
  given CirceDecoder[MarkVideoWatchedRequest] = deriveDecoder
  given Schema[MarkVideoWatchedRequest] = Schema.derived

final case class VideoWatchedResponse(
    nextPhase: String)

object VideoWatchedResponse:
  given CirceEncoder[VideoWatchedResponse] = deriveEncoder
  given CirceDecoder[VideoWatchedResponse] = deriveDecoder
  given Schema[VideoWatchedResponse] = Schema.derived

object VideoEndpoints:
  import SurveyEndpoints.sessionCookie

  // GET /api/video/next - Get next video to watch
  val nextVideo: PublicEndpoint[Option[String], ApiError, VideoResponse, Any] =
    endpoint
      .get
      .in("api" / "video" / "next")
      .in(sessionCookie)
      .out(jsonBody[VideoResponse])
      .errorOut(jsonBody[ApiError])
      .description("Get the next video to watch for the session")

  // POST /api/video/watched - Mark video as watched
  val markWatched
      : PublicEndpoint[(Option[String], MarkVideoWatchedRequest), ApiError, VideoWatchedResponse, Any] =
    endpoint
      .post
      .in("api" / "video" / "watched")
      .in(sessionCookie)
      .in(jsonBody[MarkVideoWatchedRequest])
      .out(jsonBody[VideoWatchedResponse])
      .errorOut(jsonBody[ApiError])
      .description("Mark video as watched and get next phase")
end VideoEndpoints
