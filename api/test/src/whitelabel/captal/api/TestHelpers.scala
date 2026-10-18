package whitelabel.captal.api

import io.circe.parser.decode
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.ZServerEndpoint
import whitelabel.captal.core.application.commands.NextIdentificationSurvey.given
import whitelabel.captal.core.application.commands.{
  NextAdvertiserSurvey,
  NextIdentificationSurvey,
  NextVideo
}
import whitelabel.captal.messages.AdvertiserSurveyResponse.given
import whitelabel.captal.messages.VideoResponse.given
import whitelabel.captal.messages.VideoWatchedResponse.given
import whitelabel.captal.messages.{AdvertiserSurveyResponse, VideoResponse, VideoWatchedResponse}
import zio.*

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

  private def rioMonadError[R]: MonadError[[A] =>> RIO[R, A]] =
    new MonadError[[A] =>> RIO[R, A]]:
      def unit[T](t: T): RIO[R, T] = ZIO.succeed(t)
      def map[T, T2](fa: RIO[R, T])(f: T => T2): RIO[R, T2] = fa.map(f)
      def flatMap[T, T2](fa: RIO[R, T])(f: T => RIO[R, T2]): RIO[R, T2] = fa.flatMap(f)
      def error[T](t: Throwable): RIO[R, T] = ZIO.fail(t)
      protected def handleWrappedError[T](rt: RIO[R, T])(
          h: PartialFunction[Throwable, RIO[R, T]]): RIO[R, T] = rt.catchSome(h)
      def ensure[T](f: RIO[R, T], e: => RIO[R, Unit]): RIO[R, T] = f.ensuring(e.ignore)

  /** Logs anything that dies, because otherwise nothing does.
    *
    * A defect reaches the stub interpreter and comes back as a bare 500 with the body "Internal
    * server error", and the cause is gone — no trace, no log line. The test then fails three layers
    * away on a missing session cookie. Sixty tests broke that way and the answer was never printed
    * anywhere.
    *
    * `tapErrorCause` and not `tapDefect`, which was the first guess and saw nothing: in tapir a
    * typed error is a *value* the security logic returns as a `Left`, so an effect that fails
    * instead has failed in a position tapir has no handler for — it answers 500 and the typed error
    * never becomes a response. That failure is not a defect, so `tapDefect` ran zero times while
    * ten requests came back 500.
    */
  private def logDefects[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A] = effect.tapErrorCause:
    cause => ZIO.logErrorCause("endpoint failed outside the error channel tapir renders", cause)

  private def provideEnvToEndpoint[R](
      endpoint: ZServerEndpoint[R, Any],
      env: ZEnvironment[R]): ZServerEndpoint[Any, Any] = ServerEndpoint(
    endpoint.endpoint,
    _ => a => logDefects(endpoint.securityLogic(rioMonadError)(a)).provideEnvironment(env),
    _ => u => i => logDefects(endpoint.logic(rioMonadError)(u)(i)).provideEnvironment(env)
  )

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

  /** Routes that no longer go through tapir, served to the same suites.
    *
    * This exists because converting a route off tapir removes it from `TapirStubInterpreter`, and
    * its tests would then fail for having no server rather than for anything about the route. The
    * suites speak `basicRequest` with URLs and know nothing about either library, which is what
    * makes them worth keeping unchanged across the migration — so the adapting happens here.
    *
    * zio-http first with tapir as the fallback: `runZIO` answers 404 when no route matches, and
    * that is the signal to hand the request to the endpoints that have not moved yet. The fallback
    * goes when the last one does.
    */
  private def serveZioHttp(
      routes: zio.http.Routes[Any, Nothing],
      fallback: SttpBackend[Task, Any]): SttpBackend[Task, Any] = SttpBackendStub[Task, Any](
    taskMonadError)
    .whenAnyRequest
    .thenRespondF: request =>
      routes
        .runZIO(asZioRequest(request))
        .flatMap: response =>
          if response.status == zio.http.Status.NotFound then
            fallback.send(request.asInstanceOf[Request[String, Any]])
          else
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

  def testBackend: ZIO[TestEnv, Nothing, SttpBackend[Task, Any]] = ZIO
    .environment[TestEnv]
    .map: env =>
      val surveyRoutes = env.get[SurveyRoutes]
      val localeRoutes = env.get[LocaleRoutes]
      val videoRoutes = env.get[VideoRoutes]
      val advertiserSurveyRoutes = env.get[AdvertiserSurveyRoutes]
      val finishRoutes = env.get[FinishRoutes]
      val surveyEndpoints = surveyRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      val localeEndpoints = localeRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      val videoEndpoints = videoRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      val advertiserSurveyEndpoints = advertiserSurveyRoutes
        .routes
        .map(e => provideEnvToEndpoint(e, env))
      val finishEndpoints = finishRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      val tapirBackend = TapirStubInterpreter(SttpBackendStub[Task, Any](taskMonadError))
        .whenServerEndpointsRunLogic(
          surveyEndpoints ++ localeEndpoints ++ videoEndpoints ++ advertiserSurveyEndpoints ++
            finishEndpoints)
        .backend()
      serveZioHttp(HealthRoutes.routes, tapirBackend)

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

  def parseNextSurvey(body: String): Option[NextIdentificationSurvey] =
    decode[NextIdentificationSurvey](body).toOption

  def postEmailAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, email: String) =
    postRawBody(backend, sessionCookie, "email", s"""{"answer":{"type":"text","value":"$email"}}""")

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
    .body(s"""{"answer":{"type":"single","value":"$optionId"}}""")
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
    .body(s"""{"answer":{"type":"text","value":"$text"}}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)
  def postLocationAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, optionId: String) =
    basicRequest
      .post(uri"http://test/api/survey/location")
      .cookie("captal_session", sessionCookie)
      .body(s"""{"answer":{"type":"single","value":"$optionId"}}""")
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
    .body(s"""{"answer":{"type":"single","value":"$optionId"}}""")
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
