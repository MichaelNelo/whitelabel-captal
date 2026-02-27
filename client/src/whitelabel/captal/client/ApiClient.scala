package whitelabel.captal.client

import org.scalajs.dom
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey.question.AnswerValue
import whitelabel.captal.endpoints.{AnswerRequest, ApiError, SetLocaleRequest, StatusResponse, SurveyEndpoints}
import zio.*

object ApiClient:
  private val backend: SttpBackend[Task, Any] = FetchZioBackend()
  private val baseUri: Option[Uri] = Some(uri"${dom.window.location.origin}")
  private val interpreter: SttpClientInterpreter = SttpClientInterpreter()

  private def unwrapDecodeResult[T](result: DecodeResult[T]): T =
    result match
      case DecodeResult.Value(v) => v
      case f: DecodeResult.Failure =>
        throw new RuntimeException(s"Decode failure: ${f.toString}")

  /** Get status - requires session cookie to be set */
  def getStatus(): Task[Either[ApiError, StatusResponse]] =
    interpreter
      .toClient(SurveyEndpoints.status, baseUri, backend)
      .apply(None)
      .map(unwrapDecodeResult)

  def getNextSurvey(): Task[Either[ApiError, Option[NextIdentificationSurvey]]] =
    interpreter
      .toClient(SurveyEndpoints.nextSurvey, baseUri, backend)
      .apply(None)
      .map(unwrapDecodeResult)

  def answerEmail(answer: AnswerValue): Task[Either[ApiError, Unit]] =
    interpreter
      .toClient(SurveyEndpoints.answerEmail, baseUri, backend)
      .apply((None, AnswerRequest(answer)))
      .map(unwrapDecodeResult)

  def answerProfiling(answer: AnswerValue): Task[Either[ApiError, Unit]] =
    interpreter
      .toClient(SurveyEndpoints.answerProfiling, baseUri, backend)
      .apply((None, AnswerRequest(answer)))
      .map(unwrapDecodeResult)

  def answerLocation(answer: AnswerValue): Task[Either[ApiError, Unit]] =
    interpreter
      .toClient(SurveyEndpoints.answerLocation, baseUri, backend)
      .apply((None, AnswerRequest(answer)))
      .map(unwrapDecodeResult)

  def getLocales(): Task[Either[ApiError, List[String]]] =
    interpreter
      .toClient(SurveyEndpoints.listLocales, baseUri, backend)
      .apply(())
      .map(unwrapDecodeResult)

  def setLocale(locale: String): Task[Either[ApiError, Unit]] =
    interpreter
      .toClient(SurveyEndpoints.setLocale, baseUri, backend)
      .apply((None, None, SetLocaleRequest(locale)))
      .map(unwrapDecodeResult)
      .map(_.map(_ => ()))
