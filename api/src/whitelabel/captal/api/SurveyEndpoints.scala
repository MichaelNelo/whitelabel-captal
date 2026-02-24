package whitelabel.captal.api

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder, Json}
import sttp.tapir.Schema
import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, IdentificationSurveyType, Phase}
import whitelabel.captal.core.survey.question.QuestionAnswer
import whitelabel.captal.infra.{SessionContext, SessionService}
import zio.*

final case class StatusResponse(phase: PhaseResponse)

object StatusResponse:
  given Encoder[StatusResponse] = Encoder.instance: r =>
    Json.obj("phase" -> PhaseResponse.encoder(r.phase))
  given Decoder[StatusResponse] = Decoder.instance: c =>
    c.downField("phase").as[PhaseResponse](using PhaseResponse.decoder).map(StatusResponse(_))
  given Schema[StatusResponse] = Schema.derived

enum PhaseResponse:
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserQuestion
  case Ready

object PhaseResponse:
  def from(phase: Phase): PhaseResponse =
    phase match
      case Phase.IdentificationQuestion =>
        PhaseResponse.IdentificationQuestion
      case Phase.AdvertiserVideo =>
        PhaseResponse.AdvertiserVideo
      case Phase.AdvertiserQuestion =>
        PhaseResponse.AdvertiserQuestion
      case Phase.Ready =>
        PhaseResponse.Ready

  given encoder: Encoder[PhaseResponse] = Encoder.instance:
    case IdentificationQuestion =>
      Json.obj("type" -> Json.fromString("identification_question"))
    case AdvertiserVideo =>
      Json.obj("type" -> Json.fromString("advertiser_video"))
    case AdvertiserQuestion =>
      Json.obj("type" -> Json.fromString("advertiser_question"))
    case Ready =>
      Json.obj("type" -> Json.fromString("ready"))

  given decoder: Decoder[PhaseResponse] = Decoder.instance: c =>
    c.downField("type")
      .as[String]
      .flatMap:
        case "identification_question" =>
          Right(IdentificationQuestion)
        case "advertiser_video" =>
          Right(AdvertiserVideo)
        case "advertiser_question" =>
          Right(AdvertiserQuestion)
        case "ready" =>
          Right(Ready)
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown phase type: $other", c.history))

  given Schema[PhaseResponse] = Schema.string
end PhaseResponse

final case class AnswerEmailRequest(email: String)
object AnswerEmailRequest:
  given Decoder[AnswerEmailRequest] = deriveDecoder
  given Encoder[AnswerEmailRequest] = deriveEncoder
  given Schema[AnswerEmailRequest] = Schema.derived

final case class AnswerEmailResponse(success: Boolean)
object AnswerEmailResponse:
  given Decoder[AnswerEmailResponse] = deriveDecoder
  given Encoder[AnswerEmailResponse] = deriveEncoder
  given Schema[AnswerEmailResponse] = Schema.derived

final case class AnswerProfilingRequest(optionId: String)
object AnswerProfilingRequest:
  given Decoder[AnswerProfilingRequest] = deriveDecoder
  given Encoder[AnswerProfilingRequest] = deriveEncoder
  given Schema[AnswerProfilingRequest] = Schema.derived

final case class AnswerProfilingResponse(success: Boolean)
object AnswerProfilingResponse:
  given Decoder[AnswerProfilingResponse] = deriveDecoder
  given Encoder[AnswerProfilingResponse] = deriveEncoder
  given Schema[AnswerProfilingResponse] = Schema.derived

final case class AnswerLocationRequest(optionId: String)
object AnswerLocationRequest:
  given Decoder[AnswerLocationRequest] = deriveDecoder
  given Encoder[AnswerLocationRequest] = deriveEncoder
  given Schema[AnswerLocationRequest] = Schema.derived

final case class AnswerLocationResponse(success: Boolean)
object AnswerLocationResponse:
  given Decoder[AnswerLocationResponse] = deriveDecoder
  given Encoder[AnswerLocationResponse] = deriveEncoder
  given Schema[AnswerLocationResponse] = Schema.derived

final case class NextSurveyResponse(surveyId: String, questionId: String, surveyType: String)
object NextSurveyResponse:
  given Decoder[NextSurveyResponse] = deriveDecoder
  given Encoder[NextSurveyResponse] = deriveEncoder
  given Schema[NextSurveyResponse] = Schema.derived

  def from(next: NextIdentificationSurvey): NextSurveyResponse = NextSurveyResponse(
    surveyId = next.surveyId.asString,
    questionId = next.questionId.asString,
    surveyType =
      next.surveyType match
        case IdentificationSurveyType.Email =>
          "email"
        case IdentificationSurveyType.Profiling =>
          "profiling"
        case IdentificationSurveyType.Location =>
          "location"
  )

object SurveyEndpoints:

  object AnswerEmail:
    type FlowType = Flow.Aux[Task, AnswerEmailCommand, QuestionAnswer]
    type Env = SessionContext & SessionService & FlowType

    val endpoint = SessionEndpoint
      .securedFlow[AnswerEmailCommand, QuestionAnswer]()
      .post
      .in("api" / "survey" / "email")
      .in(jsonBody[AnswerEmailRequest])
      .out(jsonBody[AnswerEmailResponse])
      .description("Answer the email identification question")

  object AnswerProfiling:
    type FlowType = Flow.Aux[Task, AnswerProfilingCommand, QuestionAnswer]
    type Env = SessionContext & SessionService & FlowType

    val endpoint = SessionEndpoint
      .securedFlow[AnswerProfilingCommand, QuestionAnswer]()
      .post
      .in("api" / "survey" / "profiling")
      .in(jsonBody[AnswerProfilingRequest])
      .out(jsonBody[AnswerProfilingResponse])
      .description("Answer a profiling survey question")

  object AnswerLocation:
    type FlowType = Flow.Aux[Task, AnswerLocationCommand, QuestionAnswer]
    type Env = SessionContext & SessionService & FlowType

    val endpoint = SessionEndpoint
      .securedFlow[AnswerLocationCommand, QuestionAnswer]()
      .post
      .in("api" / "survey" / "location")
      .in(jsonBody[AnswerLocationRequest])
      .out(jsonBody[AnswerLocationResponse])
      .description("Answer a location survey question")

  object NextIdentificationSurvey:
    type Response = ProvideNextIdentificationSurveyHandler.Response
    type FlowType = Flow.Aux[Task, ProvideNextIdentificationSurveyCommand.type, Response]
    type Env = SessionContext & SessionService & FlowType

    val endpoint = SessionEndpoint
      .securedFlow[ProvideNextIdentificationSurveyCommand.type, Response]()
      .get
      .in("api" / "survey" / "next")
      .out(jsonBody[Option[NextSurveyResponse]])
      .description("Get the next identification survey for the user")

  object Status:
    type Env = SessionContext & SessionService

    val endpoint = SessionEndpoint
      .securedSessionData(
        SessionEndpoint.OnMissing.Create(whitelabel.captal.core.user.DeviceId("unknown"), "en"))
      .get
      .in("api" / "status")
      .out(jsonBody[StatusResponse])
      .out(setCookie("session_id"))
      .description("Get the current phase/status of the user session")
end SurveyEndpoints
