package whitelabel.captal.api

import io.circe.parser.decode
import io.circe.syntax.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import whitelabel.captal.api.routes.{
  AdvertiserSurveyRoutes,
  FinishRoutes,
  HealthRoutes,
  LocaleRoutes,
  RecoveryRoutes,
  SurveyRoutes,
  TermsRoutes,
  VideoRoutes
}
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
import whitelabel.captal.api.session.{
  CurrentLocation,
  SessionCookieConfig,
  UserCookieConfig,
  UserLookup
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
    * — and a string body. No multipart and no streaming.
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

  /** The server, as an sttp backend the suites can send to: the translation between sttp's request
    * and zio-http's. The suites speak `basicRequest` with URLs and know nothing about either
    * library.
    */
  private def serveZioHttp(routes: zio.http.Routes[Any, Nothing]): SttpBackend[Task, Any] =
    // The server's own `CodecConfig`, not a copy of it — see the note on `HttpApp.codecConfig` — so
    // these suites read the wire format the deployed server produces.
    val served = routes @@ CodecConfig.withConfig(HttpApp.codecConfig)
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
                // The headers come across so `extractSessionCookie` can read `Set-Cookie` off the
                // response, which is how every suite gets a session to work with.
                Response(
                  text,
                  sttp.model.StatusCode(response.status.code),
                  "",
                  response
                    .headers
                    .toList
                    .map(h => sttp.model.Header(h.headerName, h.renderedValue)))
  end serveZioHttp

  /** Every route the dev server mounts, with the test environment supplied — the `devRoutes` among
    * them, for `/api/dev/reset-phase` and `/api/dev/fault/{kind}`.
    */
  def testBackend: ZIO[TestEnv, Nothing, SttpBackend[Task, Any]] = ZIO
    .environment[TestEnv]
    .map: env =>
      val localeRoutes = env.get[LocaleRoutes]
      serveZioHttp(
        (
          HealthRoutes.routes(env.get[ApiBase]) ++ env.get[FinishRoutes].routes ++
            localeRoutes.routes ++ localeRoutes.devRoutes ++ env.get[VideoRoutes].routes ++
            env.get[SurveyRoutes].routes ++ env.get[AdvertiserSurveyRoutes].routes ++
            env.get[RecoveryRoutes].routes ++ env.get[RecoveryRoutes].devRoutes ++
            env.get[TermsRoutes].routes
        ).provideEnvironment(env))

  /** The health check. */
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

  /** The access point the test redirect names: one a location-bound API has to have registered. */
  val PortalAp = "11:22:33:44:55:66"

  // Default captive portal headers for tests
  private val defaultPortalHeaders = Map(
    "X-Client-Mac"   -> "AA:BB:CC:DD:EE:FF",
    "X-Ap-Mac"       -> PortalAp,
    "X-Redirect-Url" -> "http://google.com",
    "X-Ssid"         -> "TestNetwork",
    "X-Fingerprint"  -> "test-fingerprint"
  )

  def getStatus(backend: SttpBackend[Task, Any], sessionCookie: Option[String] = None) =
    val base = basicRequest.get(uri"http://test/api/status").response(asStringAlways)
    val withHeaders =
      defaultPortalHeaders.foldLeft(base) { case (req, (k, v)) =>
        req.header(k, v)
      }
    val withCookie = sessionCookie.fold(withHeaders)(c => withHeaders.cookie("captal_session", c))
    withCookie.send(backend)

  /** A new session via /api/status, still at the welcome: a device nothing is known about, so in
    * `WelcomeNeedsToS`. Answers the session cookie.
    */
  def createSessionAtWelcome(backend: SttpBackend[Task, Any]): Task[String] = getStatus(
    backend,
    None).map(r => extractSessionCookie(r).get)

  /** A new session past the welcome: its device accepted the terms of service, which leaves it at
    * the identification questions, where most suites begin. Answers the session cookie.
    */
  def createSession(backend: SttpBackend[Task, Any]): Task[String] =
    for
      cookie <- createSessionAtWelcome(backend)
      _      <- acceptTerms(backend, cookie)
    yield cookie

  def getTerms(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/terms")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def acceptTerms(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .post(uri"http://test/api/terms")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def start(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .post(uri"http://test/api/start")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  /** `/api/status` alone, served as the API of `location` serves it: the tests' own serves none. */
  def statusBackendAt(location: CurrentLocation): ZIO[TestEnv, Nothing, SttpBackend[Task, Any]] =
    ZIO
      .environment[TestEnv]
      .map: env =>
        serveZioHttp(
          SurveyRoutes(
            env.get[SessionCookieConfig],
            env.get[ApiBase],
            location,
            env.get[UserCookieConfig],
            env.get[UserLookup]).routes.provideEnvironment(env))

  /** `/api/status` from a redirect that carries no fingerprint, from the device `mac`, with the
    * session cookie when one is given.
    */
  def statusWithoutFingerprintResponse(
      backend: SttpBackend[Task, Any],
      mac: String,
      sessionCookie: Option[String] = None) =
    val request = (defaultPortalHeaders - "X-Fingerprint" + ("X-Client-Mac" -> mac)).foldLeft(
      basicRequest.get(uri"http://test/api/status").response(asStringAlways)) {
      case (req, (k, v)) =>
        req.header(k, v)
    }
    sessionCookie.fold(request)(c => request.cookie("captal_session", c)).send(backend)

  /** [[statusWithoutFingerprintResponse]]'s session cookie, which a refused request does not set.
    */
  def statusWithoutFingerprint(backend: SttpBackend[Task, Any], mac: String): Task[Option[String]] =
    statusWithoutFingerprintResponse(backend, mac).map(extractSessionCookie)

  /** `/api/status` from a redirect lacking the headers `names`, with no session cookie. */
  def statusWithout(backend: SttpBackend[Task, Any], names: String*) =
    (defaultPortalHeaders -- names)
      .foldLeft(basicRequest.get(uri"http://test/api/status").response(asStringAlways)) {
        case (req, (k, v)) =>
          req.header(k, v)
      }
      .send(backend)

  /** `/api/status` from a redirect through the access point `ap`, or naming none. */
  def statusThrough(backend: SttpBackend[Task, Any], ap: Option[String]) = ap
    .fold(defaultPortalHeaders - "X-Ap-Mac")(mac => defaultPortalHeaders + ("X-Ap-Mac" -> mac))
    .foldLeft(basicRequest.get(uri"http://test/api/status").response(asStringAlways)) {
      case (req, (k, v)) =>
        req.header(k, v)
    }
    .send(backend)

  /** A new session from a device the redirect describes differently: its own fingerprint and MAC.
    */
  def createSessionFrom(
      backend: SttpBackend[Task, Any],
      fingerprint: String,
      mac: String): Task[String] =
    val headers = defaultPortalHeaders ++ Map("X-Fingerprint" -> fingerprint, "X-Client-Mac" -> mac)
    for
      cookie <- headers
        .foldLeft(basicRequest.get(uri"http://test/api/status").response(asStringAlways)) {
          case (req, (k, v)) =>
            req.header(k, v)
        }
        .send(backend)
        .map(r => extractSessionCookie(r).get)
      _ <- acceptTerms(backend, cookie)
    yield cookie

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

  /** The question out of a `/api/survey/next` body, through the wrapper the endpoint returns. */
  def parseNextSurvey(body: String): Option[NextIdentificationSurvey] = decode[SurveyResponse](body)
    .toOption
    .flatMap:
      case SurveyResponse.Survey(data) =>
        Some(data)
      case SurveyResponse.Step(_) =>
        None

  /** An answer body as the browser writes it: an `AnswerRequest` through the encoder `ApiClient`
    * uses, so every test posting an answer also exercises the request direction of the wire
    * contract. `postRawBody` is how to send what no client would.
    */
  def answerBody(value: AnswerValue): String = AnswerRequest(value).asJson.noSpaces

  /** The suites hold option ids as the strings they read out of a fixture, so this parses one back.
    * Throws, naming the string, rather than returning an `Option`.
    */
  private def singleChoice(optionId: String): String = answerBody(
    AnswerValue.SingleChoice(
      OptionId
        .fromString(optionId)
        .getOrElse(throw new IllegalArgumentException(s"Not an option id: $optionId"))))

  def postEmailAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, email: String) =
    postRawBody(backend, sessionCookie, "email", answerBody(AnswerValue.Text(email)))

  /** A body exactly as given, with no typed request in between, so it arrives unexamined.
    * `ProtocolRejectionSuite` uses it to send what a client would never send.
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

  def postStartPlayback(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      playbackId: String,
      videoUrl: String) = basicRequest
    .post(uri"http://test/api/video/playback/start")
    .cookie("captal_session", sessionCookie)
    .body(s"""{"playbackId":"$playbackId","videoUrl":"$videoUrl"}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  /** A failure on demand, `server` or `client`, through the dev endpoints. */
  def postFault(backend: SttpBackend[Task, Any], sessionCookie: String, kind: String) = basicRequest
    .post(uri"http://test/api/dev/fault/$kind")
    .cookie("captal_session", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def postRecovery(backend: SttpBackend[Task, Any], sessionCookie: String, body: String) =
    basicRequest
      .post(uri"http://test/api/recovery")
      .cookie("captal_session", sessionCookie)
      .body(body)
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

  /** The waiting page's stream for `errorId`, opened as the page opens it, and its events as they
    * arrive, each one its name and its data; the keep-alive comments left out. Open for as long as
    * the scope lasts. Not through `testBackend`, which reads a body to its end: this one has none.
    */
  def recoveryEvents(
      sessionCookie: String,
      errorId: String): ZIO[TestEnv & Scope, Throwable, Queue[(String, String)]] =
    for
      env      <- ZIO.environment[TestEnv]
      response <- env
        .get[RecoveryRoutes]
        .routes
        .provideEnvironment(env)
        .runZIO(
          zio
            .http
            .Request
            .get(zio.http.URL(zio.http.Path.root / "api" / "recovery" / errorId / "events"))
            .addHeader(
              zio
                .http
                .Header
                .Cookie(NonEmptyChunk(zio.http.Cookie.Request("captal_session", sessionCookie)))))
      events <- Queue.unbounded[(String, String)]
      _      <-
        response
          .body
          .asStream
          .via(zio.stream.ZPipeline.utf8Decode)
          .via(zio.stream.ZPipeline.splitOn("\n\n"))
          .filter(_.startsWith("event: "))
          .map: event =>
            val lines = event.linesIterator.toList
            (lines.head.stripPrefix("event: "), lines.drop(1).map(_.stripPrefix("data: ")).mkString)
          .foreach(events.offer)
          .forkScoped
    yield events

  def postRedeem(backend: SttpBackend[Task, Any], sessionCookie: String, errorId: String) =
    basicRequest
      .post(uri"http://test/api/recovery/redeem")
      .cookie("captal_session", sessionCookie)
      .body(s"""{"errorId":"$errorId"}""")
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

  /** A client report naming one frame of the suite's bundle, as the page would send it. */
  def clientReport(
      file: String = "https://cdn.test/cafe/main.js",
      line: Int = 2,
      column: Int = 10,
      name: String = "TypeError",
      message: String = "Cannot read properties of undefined (reading 'x')") =
    s"""{"report":{"bundleVersion":"test-1","name":"$name","message":"$message","frames":[{"file":"$file","line":$line,"column":$column}]}}"""

  def postEndPlayback(backend: SttpBackend[Task, Any], sessionCookie: String, playbackId: String) =
    basicRequest
      .post(uri"http://test/api/video/playback/end")
      .cookie("captal_session", sessionCookie)
      .body(s"""{"playbackId":"$playbackId"}""")
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

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

  /** The scheduler's worker runs on its own fiber and talks to rqlite, so the effect is not
    * observable synchronously. Bounded poll against the real clock.
    */
  def awaitWithLiveClock[R](check: ZIO[R, Throwable, Boolean]): ZIO[R, Throwable, Boolean] =
    (ZIO.sleep(50.millis) *> check)
      .repeatUntil(identity)
      .timeoutTo(false)(identity)(15.seconds)
      .withClock(Clock.ClockLive)
end TestHelpers
