package whitelabel.captal.api

import io.circe.parser.decode
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.ZServerEndpoint
import whitelabel.captal.core.application.commands.NextIdentificationSurvey.given
import whitelabel.captal.core.application.commands.{NextIdentificationSurvey, NextVideo}
import whitelabel.captal.endpoints.VideoResponse.given
import whitelabel.captal.endpoints.VideoWatchedResponse.given
import whitelabel.captal.endpoints.{VideoResponse, VideoWatchedResponse}
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

  private def provideEnvToEndpoint[R](
      endpoint: ZServerEndpoint[R, Any],
      env: ZEnvironment[R]): ZServerEndpoint[Any, Any] = ServerEndpoint(
    endpoint.endpoint,
    _ => a => endpoint.securityLogic(rioMonadError)(a).provideEnvironment(env),
    _ => u => i => endpoint.logic(rioMonadError)(u)(i).provideEnvironment(env)
  )

  def testBackend: ZIO[TestEnv, Nothing, SttpBackend[Task, Any]] = ZIO
    .environment[TestEnv]
    .map: env =>
      val surveyEndpoints = SurveyRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      val localeEndpoints = LocaleRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      val videoEndpoints = VideoRoutes.routes.map(e => provideEnvToEndpoint(e, env))
      TapirStubInterpreter(SttpBackendStub[Task, Any](taskMonadError))
        .whenServerEndpointsRunLogic(surveyEndpoints ++ localeEndpoints ++ videoEndpoints)
        .backend()

  def extractSessionCookie(response: Response[String]): Option[String] = response
    .header("Set-Cookie")
    .flatMap: header =>
      header
        .split(";")
        .headOption
        .flatMap: cookie =>
          if cookie.startsWith("session_id=") then
            Some(cookie.stripPrefix("session_id="))
          else
            None

  def getStatus(backend: SttpBackend[Task, Any], sessionCookie: Option[String] = None) =
    val request = basicRequest.get(uri"http://test/api/status").response(asStringAlways)
    val withCookie = sessionCookie.fold(request)(c => request.cookie("session_id", c))
    withCookie.send(backend)

  /** Create a new session via /api/status and return the session cookie */
  def createSession(backend: SttpBackend[Task, Any]): Task[String] =
    getStatus(backend, None).map(r => extractSessionCookie(r).get)

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
    val withCookie = sessionCookie.fold(request)(c => request.cookie("session_id", c))
    withCookie.send(backend)

  def getNextSurvey(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/survey/next")
    .cookie("session_id", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def parseNextSurvey(body: String): Option[NextIdentificationSurvey] =
    decode[NextIdentificationSurvey](body).toOption

  def postEmailAnswer(backend: SttpBackend[Task, Any], sessionCookie: String, email: String) =
    basicRequest
      .post(uri"http://test/api/survey/email")
      .cookie("session_id", sessionCookie)
      .body(s"""{"answer":{"type":"text","value":"$email"}}""")
      .contentType("application/json")
      .response(asStringAlways)
      .send(backend)

  def postProfilingAnswer(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      optionId: String) = basicRequest
    .post(uri"http://test/api/survey/profiling")
    .cookie("session_id", sessionCookie)
    .body(s"""{"answer":{"type":"single","value":"$optionId"}}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  // Video helpers
  def getNextVideo(backend: SttpBackend[Task, Any], sessionCookie: String) = basicRequest
    .get(uri"http://test/api/video/next")
    .cookie("session_id", sessionCookie)
    .response(asStringAlways)
    .send(backend)

  def parseNextVideo(body: String): Option[NextVideo] =
    decode[VideoResponse](body).toOption.flatMap:
      case VideoResponse.Video(data) => Some(data)
      case VideoResponse.Step(_)     => None

  def parseVideoStep(body: String): Boolean =
    decode[VideoResponse](body).toOption.exists:
      case VideoResponse.Step(_) => true
      case _                     => false

  def postMarkVideoWatched(
      backend: SttpBackend[Task, Any],
      sessionCookie: String,
      durationWatched: Int,
      completed: Boolean) = basicRequest
    .post(uri"http://test/api/video/watched")
    .cookie("session_id", sessionCookie)
    .body(s"""{"durationWatched":$durationWatched,"completed":$completed}""")
    .contentType("application/json")
    .response(asStringAlways)
    .send(backend)

  def parseVideoWatchedResponse(body: String): Option[VideoWatchedResponse] =
    decode[VideoWatchedResponse](body).toOption
end TestHelpers
