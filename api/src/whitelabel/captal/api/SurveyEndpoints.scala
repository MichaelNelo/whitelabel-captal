package whitelabel.captal.api

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema
import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.infrastructure.{SessionRepository, SurveyRepository}
import whitelabel.captal.core.survey.question.QuestionAnswer
import zio.*
import zio.interop.catz.*

// Request/Response types
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

// Endpoints organized by flow
object SurveyEndpoints:

  object AnswerEmail:
    type Env = SessionRepository[Task] & SurveyRepository[Task] & EventHandler[Task, Event]

    val endpoint = SessionEndpoint
      .securedFlow[SurveyRepository[Task], AnswerEmailCommand, QuestionAnswer](session =>
        ZIO.serviceWith[SurveyRepository[Task]](AnswerEmailHandler(session, _)))
      .post
      .in("api" / "survey" / "email")
      .in(jsonBody[AnswerEmailRequest])
      .out(jsonBody[AnswerEmailResponse])
      .description("Answer the email identification question")
