package whitelabel.captal.client

import scala.scalajs.js

import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.messages.i18n.given
import whitelabel.captal.messages.{
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

/** The API, grouped by which view calls it.
  *
  * The grouping is about reachability, not tidiness. One `ApiClient` object referenced from all six
  * views is reachable from startup, and everything it touches with it: every route, and the decoder
  * of every response type the app can receive. Nothing can be deferred, because everything is
  * needed to build the thing that reaches everything.
  *
  * Split this way, `AdvertiserSurveyResponse`'s decoder is reachable only from the view that asks
  * for one. Measured over a route-split build, that moved the deferrable share from 13% to 23% of
  * the bundle and took 22 KB gzipped out of the first paint.
  *
  * The transport stays shared in [[ApiClient]] — it is small, and every view needs it.
  */
object StatusApi:
  def getStatus(
      extraHeaders: Map[String, String] = Map.empty): js.Promise[Either[ApiError, StatusResponse]] =
    ApiClient.fetchApi[StatusResponse]("GET", ApiClient.url("/status"), extraHeaders = extraHeaders)

object WelcomeApi:
  def getNextSurvey(): js.Promise[Either[ApiError, SurveyResponse]] = ApiClient.get(
    ApiClient.url("/survey/next"))

  def setLocale(locale: String): js.Promise[Either[ApiError, StatusResponse]] = ApiClient.putJson(
    ApiClient.url("/session/locale"),
    SetLocaleRequest(locale))

  def getLocales(): js.Promise[Either[ApiError, List[String]]] = ApiClient.get(
    ApiClient.url("/locales"))

object QuestionApi:
  def answerEmail(answer: AnswerValue): js.Promise[Either[ApiError, SurveyResponse]] = ApiClient
    .postJson(ApiClient.url("/survey/email"), AnswerRequest(answer))

  def answerProfiling(answer: AnswerValue): js.Promise[Either[ApiError, SurveyResponse]] = ApiClient
    .postJson(ApiClient.url("/survey/profiling"), AnswerRequest(answer))

  def answerLocation(answer: AnswerValue): js.Promise[Either[ApiError, SurveyResponse]] = ApiClient
    .postJson(ApiClient.url("/survey/location"), AnswerRequest(answer))

object VideoApi:
  def getNextVideo(): js.Promise[Either[ApiError, VideoResponse]] = ApiClient.get(
    ApiClient.url("/video/next"))

  def markVideoWatched(
      durationWatched: Int,
      completed: Boolean): js.Promise[Either[ApiError, VideoWatchedResponse]] = ApiClient.postJson(
    ApiClient.url("/video/watched"),
    MarkVideoWatchedRequest(durationWatched, completed))

object AdvertiserApi:
  def getNextAdvertiserSurvey(): js.Promise[Either[ApiError, AdvertiserSurveyResponse]] = ApiClient
    .get(ApiClient.url("/survey/advertiser/next"))

  def answerAdvertiser(
      answer: AnswerValue): js.Promise[Either[ApiError, AdvertiserSurveyResponse]] = ApiClient
    .postJson(ApiClient.url("/survey/advertiser"), AnswerRequest(answer))

object ReadyApi:
  /** POST `/api/finish`. Triggers `UnifiAccessHandler` post-commit. The response reflects the
    * session **after** UniFi ran: on success `phase = Authorized` with the countdown seeded; on
    * UniFi failure or no config, `phase = Ready` and the caller can retry.
    */
  def finish(): js.Promise[Either[ApiError, StatusResponse]] = ApiClient.post(
    ApiClient.url("/finish"))

  def resetPhase(): js.Promise[Either[ApiError, StatusResponse]] = ApiClient.post(
    ApiClient.url("/dev/reset-phase"))

object I18nApi:
  def getI18n(locale: String): js.Promise[Either[ApiError, I18n]] = ApiClient.get(
    ApiClient.url(s"/i18n/$locale"))
