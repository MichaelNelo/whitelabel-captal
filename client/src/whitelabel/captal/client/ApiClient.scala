package whitelabel.captal.client

import org.scalajs.dom
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.endpoints.{
  AnswerRequest,
  ApiError,
  LocaleEndpoints,
  MarkVideoWatchedRequest,
  SetLocaleRequest,
  StatusResponse,
  SurveyEndpoints,
  SurveyResponse,
  VideoEndpoints,
  VideoResponse,
  VideoWatchedResponse
}
import zio.*

object ApiClient:
  private val backend: SttpBackend[Task, Any] = FetchZioBackend()
  private val baseUri: Option[Uri] = Some(uri"${dom.window.location.origin}")
  private val interpreter: SttpClientInterpreter = SttpClientInterpreter()

  private def unwrapDecodeResult[T](result: DecodeResult[T]): T =
    result match
      case DecodeResult.Value(v) =>
        v
      case f: DecodeResult.Failure =>
        throw new RuntimeException(s"Decode failure: ${f.toString}")

  /** Get status - creates session if needed */
  def getStatus(): Task[Either[ApiError, StatusResponse]] = interpreter
    .toClient(SurveyEndpoints.status, baseUri, backend)
    .apply(None)
    .map(unwrapDecodeResult)
    .map(_.map(_._2)) // Extract StatusResponse from (cookie, response) tuple

  def getNextSurvey(): Task[Either[ApiError, SurveyResponse]] = interpreter
    .toClient(SurveyEndpoints.nextSurvey, baseUri, backend)
    .apply(None)
    .map(unwrapDecodeResult)

  def answerEmail(answer: AnswerValue): Task[Either[ApiError, SurveyResponse]] = interpreter
    .toClient(SurveyEndpoints.answerEmail, baseUri, backend)
    .apply((None, AnswerRequest(answer)))
    .map(unwrapDecodeResult)

  def answerProfiling(answer: AnswerValue): Task[Either[ApiError, SurveyResponse]] = interpreter
    .toClient(SurveyEndpoints.answerProfiling, baseUri, backend)
    .apply((None, AnswerRequest(answer)))
    .map(unwrapDecodeResult)

  def answerLocation(answer: AnswerValue): Task[Either[ApiError, SurveyResponse]] = interpreter
    .toClient(SurveyEndpoints.answerLocation, baseUri, backend)
    .apply((None, AnswerRequest(answer)))
    .map(unwrapDecodeResult)

  def getLocales(): Task[Either[ApiError, List[String]]] = interpreter
    .toClient(LocaleEndpoints.listLocales, baseUri, backend)
    .apply(())
    .map(unwrapDecodeResult)

  def setLocale(locale: String): Task[Either[ApiError, StatusResponse]] = interpreter
    .toClient(LocaleEndpoints.setLocale, baseUri, backend)
    .apply((None, None, SetLocaleRequest(locale)))
    .map(unwrapDecodeResult)
    .map(_.map(_._2))

  def getI18n(locale: String): Task[Either[ApiError, I18n]] = interpreter
    .toClient(LocaleEndpoints.getI18n, baseUri, backend)
    .apply(locale)
    .map(unwrapDecodeResult)

  // Dev-only: Reset session phase to Welcome
  def resetPhase(): Task[Either[ApiError, StatusResponse]] = interpreter
    .toClient(LocaleEndpoints.resetPhase, baseUri, backend)
    .apply(None)
    .map(unwrapDecodeResult)

  def getNextVideo(): Task[Either[ApiError, VideoResponse]] = interpreter
    .toClient(VideoEndpoints.nextVideo, baseUri, backend)
    .apply(None)
    .map(unwrapDecodeResult)

  def markVideoWatched(
      durationWatched: Int,
      completed: Boolean): Task[Either[ApiError, VideoWatchedResponse]] = interpreter
    .toClient(VideoEndpoints.markWatched, baseUri, backend)
    .apply((None, MarkVideoWatchedRequest(durationWatched, completed)))
    .map(unwrapDecodeResult)
end ApiClient
