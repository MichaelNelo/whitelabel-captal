package whitelabel.captal.client

import io.circe.{Decoder, Encoder, parser}
import io.circe.syntax.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.endpoints.{
  AnswerRequest,
  ApiError,
  MarkVideoWatchedRequest,
  SetLocaleRequest,
  StatusResponse,
  SurveyResponse,
  VideoResponse,
  VideoWatchedResponse
}
import whitelabel.captal.endpoints.i18n.given

object ApiClient:
  private def fetchApi[A: Decoder](
      method: String,
      path: String,
      body: Option[io.circe.Json] = None
  ): Future[Either[ApiError, A]] =
    val options = js.Dynamic.literal(method = method)
    body.foreach { json =>
      options.headers = js.Dynamic.literal("Content-Type" -> "application/json")
      options.body = json.noSpaces
    }
    for
      response <- dom.fetch(path, options.asInstanceOf[dom.RequestInit]).toFuture
      text     <- response.text().toFuture
    yield
      if response.ok then
        parser.decode[A](text).left.map(e => ApiError.InternalError(e.getMessage))
      else
        parser.decode[ApiError](text) match
          case Right(err) => Left(err)
          case Left(_)    => Left(ApiError.InternalError(s"HTTP ${response.status}: $text"))

  private def get[A: Decoder](path: String): Future[Either[ApiError, A]] =
    fetchApi("GET", path)

  private def post[A: Decoder](path: String): Future[Either[ApiError, A]] =
    fetchApi("POST", path)

  private def postJson[B: Encoder, A: Decoder](path: String, body: B): Future[Either[ApiError, A]] =
    fetchApi("POST", path, Some(body.asJson))

  private def putJson[B: Encoder, A: Decoder](path: String, body: B): Future[Either[ApiError, A]] =
    fetchApi("PUT", path, Some(body.asJson))

  def getStatus(): Future[Either[ApiError, StatusResponse]] =
    get("/api/status")

  def getNextSurvey(): Future[Either[ApiError, SurveyResponse]] =
    get("/api/survey/next")

  def answerEmail(answer: AnswerValue): Future[Either[ApiError, SurveyResponse]] =
    postJson("/api/survey/email", AnswerRequest(answer))

  def answerProfiling(answer: AnswerValue): Future[Either[ApiError, SurveyResponse]] =
    postJson("/api/survey/profiling", AnswerRequest(answer))

  def answerLocation(answer: AnswerValue): Future[Either[ApiError, SurveyResponse]] =
    postJson("/api/survey/location", AnswerRequest(answer))

  def getLocales(): Future[Either[ApiError, List[String]]] =
    get("/api/locales")

  def setLocale(locale: String): Future[Either[ApiError, StatusResponse]] =
    putJson("/api/session/locale", SetLocaleRequest(locale))

  def getI18n(locale: String): Future[Either[ApiError, I18n]] =
    get(s"/api/i18n/$locale")

  def resetPhase(): Future[Either[ApiError, StatusResponse]] =
    post("/api/dev/reset-phase")

  def getNextVideo(): Future[Either[ApiError, VideoResponse]] =
    get("/api/video/next")

  def markVideoWatched(
      durationWatched: Int,
      completed: Boolean): Future[Either[ApiError, VideoWatchedResponse]] =
    postJson("/api/video/watched", MarkVideoWatchedRequest(durationWatched, completed))
end ApiClient
