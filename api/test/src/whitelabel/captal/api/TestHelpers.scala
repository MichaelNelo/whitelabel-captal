package whitelabel.captal.api

import io.circe.parser.decode
import io.circe.syntax.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import whitelabel.captal.core.application.commands.{
  NextAdvertiserSurvey,
  NextIdentificationSurvey,
  NextVideo
}
import whitelabel.captal.core.survey.question.{AnswerValue, OptionId}
import whitelabel.captal.messages.AdvertiserSurveyResponse.given
import whitelabel.captal.messages.AnswerRequest.given
import whitelabel.captal.messages.SurveyResponse.given
import whitelabel.captal.messages.VideoResponse.given
import whitelabel.captal.messages.VideoWatchedResponse.given
import whitelabel.captal.messages.{
  AdvertiserSurveyResponse,
  AnswerRequest,
  SurveyResponse,
  VideoResponse,
  VideoWatchedResponse
}
import zio.*
import zio.http.codec.CodecConfig

object TestHelpers:
  type TestEnv = TestLayers.TestEnv

  given taskMonadError: MonadError[Task] with
    def unit[T](t: T): Task[T] = ZIO.succeed(t)
    def map[T, T2](fa: Task[T])(f: T => T2): Task[T2] = fa.map(f)
    def flatMap[T, T2](fa: Task[T])(f: T => Task[T2]): Task[T2] = fa.flatMap(f)
    def error[T](t: Throwable): Task[T] = ZIO.fail(t)
    protected def handleWrappedError[T](rt: Task[T])(
        h: PartialFunction[Throwable, Task[T]]): Task[T] = rt.catchSome(h)
    def ensure[T](f: Task[T], e: => Task[Unit]): Task[T] = f.ensuring(e.ignore)

  /** The sttp request as zio-http sees it.
    *
    * Only what the suites actually send: a method, a path, headers — the session cookie among them
    * — and a string body. No multipart and no streaming; if a test needs one, this is where it gets
    * added rather than worked around.
    */
  private def asZioRequest(request: Request[?, ?]): zio.http.Request =
    val body =
      request.body match
        case StringBody(text, _, _) =>
          zio.http.Body.fromString(text)
        case _ =>
          zio.http.Body.empty
    zio
      .http
      .Request(
        version = zio.http.Version.Http_1_1,
        method = zio.http.Method.fromString(request.method.method),
        url = zio.http.URL.decode(request.uri.toString).getOrElse(zio.http.URL.empty),
        headers = zio
          .http
          .Headers(request.headers.map(h => zio.http.Header.Custom(h.name, h.value))),
        body = body,
        remoteAddress = None
      )

  /** The server, as an sttp backend the suites can send to.
    *
    * It began as an adapter with a tapir fallback, so that a route converting to zio-http did not
    * take its tests down with it — the suites speak `basicRequest` with URLs and know nothing about
    * either library, which is what made them worth keeping unchanged across the migration. The
    * fallback is gone with the last tapir route; what is left is the translation between sttp's
    * request and zio-http's.
    */
  private def serveZioHttp(routes: zio.http.Routes[Any, Nothing]): SttpBackend[Task, Any] =
    // The server's own `CodecConfig`, not a copy of it — see the note on `Main.codecConfig`.
    // Without it these suites would be reading a wire format the deployed server does not produce,
    // which is how an empty `options` list came to vanish from a radio question's response while
    // every test stayed green.
    val served = routes @@ CodecConfig.withConfig(Main.codecConfig)
    SttpBackendStub[Task, Any](taskMonadError)
      .whenAnyRequest
      .thenRespondF: request =>
        served
          .runZIO(asZioRequest(request))
          .flatMap: response =>
            response
              .body
              .asString
              .orElseSucceed("")
              .map: text =>
                // The headers come across because `extractSessionCookie` reads `Set-Cookie` off
                // the response, which is how every suite gets a session to work with.
                Response(
                  text,
                  sttp.model.StatusCode(response.status.code),
                  "",
                  response
                    .headers
                    .toList
                    .map(h => sttp.model.Header(h.headerName, h.renderedValue)))
  end serveZioHttp

  /** Every route the dev server mounts, with the test environment supplied.
    *
    * `devRoutes` among them because `/api/dev/reset-phase` is what `TestFixtures` resets with, and
    * the suites run as the dev server does.
    */
  def testBackend: ZIO[TestEnv, Nothing, SttpBackend[Task, Any]] = ZIO
    .environment[TestEnv]
    .map: env =>
      val localeRoutes = env.get[LocaleRoutes]
      serveZioHttp(
        (
          HealthRoutes.routes ++ env.get[FinishRoutes].routes ++ localeRoutes.routes ++
            localeRoutes.devRoutes ++ env.get[VideoRoutes].routes ++ env.get[SurveyRoutes].routes ++
            env.get[AdvertiserSurveyRoutes].routes
        ).provideEnvironment(env))

  /** The health check, which is the one route already off tapir. */
  def getHealth(backend: SttpBackend[Task, Any]) = basicRequest
    .get(uri"http://test/api/health")
    .response(asStringAlways)
    .send(backend)

  def extractSessionCookie(response: Response[String]): Option[String] = response
    .header("Set-Cookie")
    .flatMap: header =>
      header
        .split(";")
        .headOption
        .flatMap: cookie =>
          if cookie.startsWith("captal_session=") then
            Some(cookie.stripPrefix("captal_session="))
          else
            None

  // Default captive portal headers for tests
  private val defaultPortalHeaders = Map(
    "X-Client-Mac"   -> "AA:BB:CC:DD:EE:FF",
    "X-Ap-Mac"       -> "11:22:33:44:55:66",
    "X-Redirect-Url" -> "http://google.com",
    "X-Ssid"         -> "TestNetwork",
    "X-Click-Id"     -> "test-click-id"
  )

  def getStatus(backend: SttpBackend[Task, Any], sessionCookie: Option[String] = None) =
    val base = basicRequest.get(uri"http://test/api/status").response(asStringAlways)
    val withHeaders =
      defaultPortalHeaders.foldLeft(base) { case (req, (k, v)) =>
        req.header(k, v)
      }
    val withCookie = sessionCookie.fold(withHeaders)(c => withHeaders.cookie("captal_session", c))
    withCookie.send(backend)

  /** Create a new session via /api/status and return the session cookie */
  def createSession(backend: SttpBackend[Task, Any]): Task[String] = getStatus(backend, None).map(
    r => extractSessionCookie(r).get)

  def getLocales(backend: SttpBackend[Task, Any]) = basicRequest
    .get(uri"http://test/api/locales")
    .response(asStringAlways)
    .send(backend)

  def putSetLocale(
      backend: SttpBackend[Task, Any],
      locale: String,
      sessionCookie: Option[String] = None) =
    val request = basicRequest
      .put(uri"http://test/api/session/locale")
      .body(s"""{"locale":"$locale"}""")
      .contentType("application/json")
      .response(asStringAlways)
    val withCookie = sessionCookie.fold(request)(c => request.cookie("captal_session", c))
    withCookie.send(backend)

  def getNextSurvey(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/survey/next")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  /** The question out of a `/api/survey/next` body, through the wrapper the endpoint returns.
    *
    * This decoded `NextIdentificationSurvey` straight off the body, which worked only because the
    * old codec flattened the payload into the same object and left `type` beside it for circe to
    * ignore. The wrapper is a real object now, so reading the response means naming it — the way
    * `parseNextAdvertiserSurvey` always had to.
    */
  def parseNextSurvey(body: String): Option[NextIdentificationSurvey] = decode[SurveyResponse](body)
    .toOption
    .flatMap:
      case SurveyResponse.Survey(data) =>
        Some(data)
      case SurveyResponse.Step(_) =>
        None

  /** An answer body as the browser writes it.
    *
    * These were hand-written JSON — `{"answer":{"type":"text","value":"…"}}` — which made the
    * helpers one more independent rendering of a format that already had several. Encoding an
    * `AnswerRequest` instead means the suites send what `ApiClient` sends, so every test posting an
    * answer now also exercises the request direction of the wire contract: the browser's circe
    * encoder against whatever the server decodes with.
    *
    * `postRawBody` stays for `ProtocolRejectionSuite`, which needs to send what no client would.
    */
  def answerBody(value: AnswerValue): String = AnswerRequest(value).asJson.noSpaces

  /** The suites hold option ids as the strings they read out of a fixture, so this parses one back.
    *
    * Throws rather than returning an `Option`: a test that reaches here with something that is not
    * an option id has a broken fixture, and the useful outcome is a loud failure naming the string.
    */
  private def singleChoice(optionId: String): String = answerBody(
    AnswerValue.SingleChoice(
      OptionId
        .fromString(optionId)
        .getOrElse(throw new IllegalArgumentException(s"Not an option id: $optionId"))))

  def postEmailAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, email: String) =
    postRawBody(backend, sessionCookie, "email", answerBody(AnswerValue.Text(email)))

  /** A body exactly as given, with no typed request in between.
    *
    * Every other helper builds valid JSON, which is the point of them and the reason nothing here
    * has ever exercised an invalid one. `ProtocolRejectionSuite` needs to send what a client would
    * never send, so the body has to arrive unexamined.
    */
  def postRawBody(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      survey: String,
      body: String) = basicRequest
    .post(uri"http://test/api/survey/$survey")
    .cookie("captal_session", sessionCookie)
    .body(body)
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  def postProfilingAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      optionId: String) = basicRequest
    .post(uri"http://test/api/survey/profiling")
    .cookie("captal_session", sessionCookie)
    .body(singleChoice(optionId))
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  /** `postProfilingAnswer` sends a SingleChoice, which an `input` question cannot accept. */
  def postProfilingTextAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      text: String) = basicRequest
    .post(uri"http://test/api/survey/profiling")
    .cookie("captal_session", sessionCookie)
    .body(answerBody(AnswerValue.Text(text)))
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)
  def postLocationAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, optionId: String) =
    basicRequest
      .post(uri"http://test/api/survey/location")
      .cookie("captal_session", sessionCookie)
      .body(singleChoice(optionId))
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

  // Video helpers
  def getNextVideo(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/video/next")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def parseNextVideo(body: String): Option[NextVideo] = decode[VideoResponse](body)
    .toOption
    .flatMap:
      case VideoResponse.Video(data) =>
        Some(data)
      case VideoResponse.Step(_) =>
        None

  def parseVideoStep(body: String): Boolean = decode[VideoResponse](body)
    .toOption
    .exists:
      case VideoResponse.Step(_) =>
        true
      case _ =>
        false

  def postMarkVideoWatched(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      durationWatched: Int,
      completed: Boolean) = basicRequest
    .post(uri"http://test/api/video/watched")
    .cookie("captal_session", sessionCookie)
    .body(s"""{"durationWatched":$durationWatched,"completed":$completed}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  def parseVideoWatchedResponse(body: String): Option[VideoWatchedResponse] =
    decode[VideoWatchedResponse](body).toOption

  // Advertiser survey helpers
  def getNextAdvertiserSurvey(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/survey/advertiser/next")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def postAdvertiserAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      optionId: String) = basicRequest
    .post(uri"http://test/api/survey/advertiser")
    .cookie("captal_session", sessionCookie)
    .body(singleChoice(optionId))
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  def parseNextAdvertiserSurvey(body: String): Option[NextAdvertiserSurvey] =
    decode[AdvertiserSurveyResponse](body)
      .toOption
      .flatMap:
        case AdvertiserSurveyResponse.Survey(data) =>
          Some(data)
        case AdvertiserSurveyResponse.Step(_) =>
          None

  def parseAdvertiserSurveyStep(body: String): Boolean = decode[AdvertiserSurveyResponse](body)
    .toOption
    .exists:
      case AdvertiserSurveyResponse.Step(_) =>
        true
      case _ =>
        false

  // Finish helpers
  def postFinish(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .post(uri"http://test/api/finish")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)
end TestHelpers
