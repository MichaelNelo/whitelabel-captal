package whitelabel.captal.client

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, parser}
import org.scalajs.dom
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.endpoints.i18n.given
import whitelabel.captal.endpoints.{
  AdvertiserSurveyResponse,
  AnswerRequest,
  ApiError,
  MarkVideoWatchedRequest,
  SetLocaleRequest,
  StatusResponse,
  SurveyResponse,
  VideoResponse,
  VideoWatchedResponse
}

object ApiClient:
  /** API base path. In production the page is served at `/<slug>/index.html`, so we extract the
    * first path segment and prefix all API calls with `/<slug>/api`. In dev the page is served at
    * `/` and there is no prefix segment, so we fall back to `/api`.
    */
  private val apiBase: String =
    val firstSegment = dom
      .window
      .location
      .pathname
      .split("/")
      .find(_.nonEmpty)
    firstSegment match
      case Some(slug) if slug != "api" => s"/$slug/api"
      case _                           => "/api"

  private inline def url(path: String): String = s"$apiBase$path"

  private def fetchApi[A: Decoder](
      method: String,
      path: String,
      body: Option[io.circe.Json] = None,
      extraHeaders: Map[String, String] = Map.empty): Future[Either[ApiError, A]] =
    val options = js.Dynamic.literal(method = method)
    val headers = js.Dynamic.literal()
    body.foreach { json =>
      headers.updateDynamic("Content-Type")("application/json")
      options.body = json.noSpaces
    }
    extraHeaders.foreach { (k, v) =>
      headers.updateDynamic(k)(v)
    }
    if js.Object.keys(headers.asInstanceOf[js.Object]).length > 0 then
      options.headers = headers
    for
      response <- dom.fetch(path, options.asInstanceOf[dom.RequestInit]).toFuture
      text     <- response.text().toFuture
    yield
      if response.ok then
        parser.decode[A](text).left.map(e => ApiError.InternalError(e.getMessage))
      else
        parser.decode[ApiError](text) match
          case Right(err) =>
            Left(err)
          case Left(_) =>
            Left(ApiError.InternalError(s"HTTP ${response.status}: $text"))
  end fetchApi

  private def get[A: Decoder](path: String): Future[Either[ApiError, A]] = fetchApi("GET", path)

  private def post[A: Decoder](path: String): Future[Either[ApiError, A]] = fetchApi("POST", path)

  private def postJson[B: Encoder, A: Decoder](path: String, body: B): Future[Either[ApiError, A]] =
    fetchApi("POST", path, Some(body.asJson))

  private def putJson[B: Encoder, A: Decoder](path: String, body: B): Future[Either[ApiError, A]] =
    fetchApi("PUT", path, Some(body.asJson))

  def getStatus(
      extraHeaders: Map[String, String] = Map.empty): Future[Either[ApiError, StatusResponse]] =
    fetchApi("GET", url("/status"), extraHeaders = extraHeaders)

  def getNextSurvey(): Future[Either[ApiError, SurveyResponse]] = get(url("/survey/next"))

  def answerEmail(answer: AnswerValue): Future[Either[ApiError, SurveyResponse]] = postJson(
    url("/survey/email"),
    AnswerRequest(answer))

  def answerProfiling(answer: AnswerValue): Future[Either[ApiError, SurveyResponse]] = postJson(
    url("/survey/profiling"),
    AnswerRequest(answer))

  def answerLocation(answer: AnswerValue): Future[Either[ApiError, SurveyResponse]] = postJson(
    url("/survey/location"),
    AnswerRequest(answer))

  def getLocales(): Future[Either[ApiError, List[String]]] = get(url("/locales"))

  def setLocale(locale: String): Future[Either[ApiError, StatusResponse]] = putJson(
    url("/session/locale"),
    SetLocaleRequest(locale))

  def getI18n(locale: String): Future[Either[ApiError, I18n]] = get(url(s"/i18n/$locale"))

  def resetPhase(): Future[Either[ApiError, StatusResponse]] = post(url("/dev/reset-phase"))

  def getNextVideo(): Future[Either[ApiError, VideoResponse]] = get(url("/video/next"))

  def markVideoWatched(
      durationWatched: Int,
      completed: Boolean): Future[Either[ApiError, VideoWatchedResponse]] = postJson(
    url("/video/watched"),
    MarkVideoWatchedRequest(durationWatched, completed))

  def getNextAdvertiserSurvey(): Future[Either[ApiError, AdvertiserSurveyResponse]] = get(
    url("/survey/advertiser/next"))

  def answerAdvertiser(answer: AnswerValue): Future[Either[ApiError, AdvertiserSurveyResponse]] =
    postJson(url("/survey/advertiser"), AnswerRequest(answer))
end ApiClient
