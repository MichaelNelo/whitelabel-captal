package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.{RichZServerEndpoint, ZServerEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository, VideoRepository}
import whitelabel.captal.infra.eventhandlers.{
  AnswerPersistenceHandler,
  DbEventHandler,
  SessionPhaseHandler,
  SessionSurveyHandler,
  SessionVideoHandler,
  SurveyProgressHandler,
  TransactionalEventHandler,
  UserPersistenceHandler
}
import whitelabel.captal.infra.RqliteDataSource
import whitelabel.captal.infra.repositories.{SurveyRepositoryQuill, UserRepositoryQuill, VideoRepositoryQuill}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.services.LocaleService
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*
import zio.http.*
import zio.interop.catz.*

object Main extends ZIOAppDefault:

  // Request/Response logging middleware
  private def loggingMiddleware[R]: Middleware[R] = new Middleware[R]:
    def apply[Env1 <: R, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      routes.transform[Env1]: handler =>
        zio.http.Handler.fromFunctionZIO[Request]: request =>
          val startTime = java.lang.System.currentTimeMillis()
          handler
            .runZIO(request)
            .tap: response =>
              val duration = java.lang.System.currentTimeMillis() - startTime
              val method = request.method.toString
              val path = request.url.path.toString
              val status = response.status.code
              ZIO.logInfo(s"$method $path -> $status (${duration}ms)")
            .tapError: error =>
              val duration = java.lang.System.currentTimeMillis() - startTime
              val method = request.method.toString
              val path = request.url.path.toString
              ZIO.logError(s"$method $path -> ERROR (${duration}ms): $error")
  // Phase after identification question:
  // - Prod: Phase.AdvertiserVideo (full flow: Welcome -> Identification -> Video -> Ready)
  // - Dev: Phase.Ready (skip videos: Welcome -> Identification -> Ready)
  private val nextPhaseAfterIdentificationQuestion: Phase = Phase.AdvertiserVideo
  private val nextStepAfterIdentificationQuestion: NextStep = NextStep(
    nextPhaseAfterIdentificationQuestion)

  // Phase after advertiser video:
  // - Prod: Phase.AdvertiserVideoSurvey (when video surveys are implemented)
  // - Dev: Phase.Ready (skip video surveys for now)
  private val nextPhaseAfterVideo: Phase = Phase.Ready
  type FullEnv =
    SessionContext & SessionService & LocaleService & SurveyRoutes.AnswerEmailFlowType &
      SurveyRoutes.AnswerProfilingFlowType & SurveyRoutes.AnswerLocationFlowType &
      SurveyRoutes.NextSurveyFlowType & VideoRoutes.NextVideoFlowType &
      VideoRoutes.MarkVideoWatchedFlowType

  private val healthEndpoints: List[ZServerEndpoint[FullEnv, Any]] = HealthRoutes
    .routes
    .map(_.widen[FullEnv])

  private val surveyEndpoints: List[ZServerEndpoint[FullEnv, Any]] = SurveyRoutes
    .routes
    .map(_.widen[FullEnv])

  private val localeEndpoints: List[ZServerEndpoint[FullEnv, Any]] = LocaleRoutes
    .routes
    .map(_.widen[FullEnv])

  private val videoEndpoints: List[ZServerEndpoint[FullEnv, Any]] = VideoRoutes
    .routes
    .map(_.widen[FullEnv])

  private val devOnlyEndpoints: List[ZServerEndpoint[FullEnv, Any]] = LocaleRoutes
    .devRoutes
    .map(_.widen[FullEnv])

  private def endpoints(devEndpoints: Boolean): List[ZServerEndpoint[FullEnv, Any]] =
    val base = healthEndpoints ++ surveyEndpoints ++ localeEndpoints ++ videoEndpoints
    if devEndpoints then
      base ++ devOnlyEndpoints
    else
      base

  private def apiRoutes(devEndpoints: Boolean) = ZioHttpInterpreter().toHttp(endpoints(devEndpoints))

  // Static file serving for client (dev mode only)
  // In production, nginx or similar serves static files
  private def devStaticRoutes: Routes[Any, Response] = Routes(
    // Serve assets
    Method.GET / "assets" / "styles.css" ->
      zio.http.Handler.fromFile(java.io.File("client/assets/styles.css")),
    Method.GET / "assets" / "brand-icon.svg" ->
      zio.http.Handler.fromFile(java.io.File("client/assets/brand-icon.svg")),
    // Legacy path for brand icon (keep for compatibility)
    Method.GET / "brand-icon.svg" ->
      zio.http.Handler.fromFile(java.io.File("client/assets/brand-icon.svg")),
    // Serve compiled JS (fastLinkJS for local dev, fullLinkJS for Docker)
    Method.GET / "out" / "client" / "fastLinkJS.dest" / "main.js" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fastLinkJS.dest/main.js")),
    Method.GET / "out" / "client" / "fastLinkJS.dest" / "main.js.map" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fastLinkJS.dest/main.js.map")),
    Method.GET / "out" / "client" / "fullLinkJS.dest" / "main.js" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fullLinkJS.dest/main.js")),
    Method.GET / "out" / "client" / "fullLinkJS.dest" / "main.js.map" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fullLinkJS.dest/main.js.map"))
  ).handleError(e => Response.internalServerError(e.getMessage))

  // SPA routes: serve index.html for client-side routing (dev mode only)
  // Explicitly define SPA routes to avoid matching /api/*
  private def spaCatchAllRoutes: Routes[Any, Response] =
    val serveIndex = zio
      .http
      .Handler
      .fromFunctionZIO[Request]: _ =>
        ZIO
          .attemptBlocking(
            java.nio.file.Files.readAllBytes(java.io.File("client/index.html").toPath))
          .map(bytes =>
            Response(
              body = Body.fromArray(bytes),
              headers = Headers(Header.ContentType(MediaType.text.html))))
          .orElse(ZIO.succeed(Response.notFound))

    Routes(
      Method.GET / ""         -> serveIndex,
      Method.GET / "question" -> serveIndex,
      Method.GET / "video"    -> serveIndex,
      Method.GET / "ready"    -> serveIndex)

  private def routes(devMode: Boolean, devEndpoints: Boolean): Routes[FullEnv, Response] =
    val baseRoutes =
      if devMode then devStaticRoutes ++ apiRoutes(devEndpoints) ++ spaCatchAllRoutes
      else apiRoutes(devEndpoints)
    baseRoutes @@ loggingMiddleware

  private case class ServerSettings(config: Server.Config, devMode: Boolean, devEndpoints: Boolean)

  private val serverSettingsLayer: ZLayer[Any, Throwable, ServerSettings] = ZLayer.fromZIO:
    ZIO.attempt:
      val c = ConfigFactory.load()
      val config = Server
        .Config
        .default
        .binding(c.getString("server.host"), c.getInt("server.port"))
      val devMode = c.getBoolean("server.dev-mode")
      val devEndpoints = c.getBoolean("server.dev-endpoints")
      ServerSettings(config, devMode, devEndpoints)

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  private val dataSourceLayer = RqliteDataSource.layer

  private val sessionServiceLayer = SessionService.layer

  private val localeServiceLayer = LocaleService.layer

  private val surveyRepoLayer = SurveyRepositoryQuill.layer

  private val userRepoLayer = UserRepositoryQuill.layer

  private val videoRepoLayer = VideoRepositoryQuill.layer

  private val eventHandlerLayer
      : ZLayer[QuillSqlite & SessionContext, Nothing, EventHandler[Task, Event]] = ZLayer
    .fromFunction: (quill: QuillSqlite, ctx: SessionContext) =>
      val dbHandler = AnswerPersistenceHandler(ctx)
        .andThen(UserPersistenceHandler(ctx))
        .andThen(SessionPhaseHandler(ctx, nextPhaseAfterIdentificationQuestion, nextPhaseAfterVideo))
        .andThen(SessionSurveyHandler(ctx))
        .andThen(SessionVideoHandler(ctx))
        .andThen(SurveyProgressHandler())
      TransactionalEventHandler(dbHandler, quill)

  private val answerEmailFlowLayer: ZLayer[SurveyRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, AnswerEmailCommand, NextStep]] =
    ZLayer.fromFunction:
      (surveyRepo: SurveyRepository[Task], eventHandler: EventHandler[Task, Event]) =>
        Flow(AnswerEmailHandler(surveyRepo, nextStepAfterIdentificationQuestion), eventHandler)

  private val answerProfilingFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerProfilingCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(
        AnswerProfilingHandler(surveyRepo, userRepo, nextStepAfterIdentificationQuestion),
        eventHandler)

  private val answerLocationFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerLocationCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(
        AnswerLocationHandler(surveyRepo, userRepo, nextStepAfterIdentificationQuestion),
        eventHandler)

  private val nextSurveyFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[
      Task,
      ProvideNextIdentificationSurveyCommand.type,
      ProvideNextIdentificationSurveyHandler.Response]
  ] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(
        ProvideNextIdentificationSurveyHandler(
          surveyRepo,
          userRepo,
          nextPhaseAfterIdentificationQuestion),
        eventHandler)

  private val nextVideoFlowLayer: ZLayer[
    VideoRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, ProvideNextVideoCommand.type, ProvideNextVideoHandler.Response]
  ] = ZLayer.fromFunction:
    (
        videoRepo: VideoRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(ProvideNextVideoHandler(videoRepo, userRepo, nextPhaseAfterVideo), eventHandler)

  // Custom Flow for MarkVideoWatched that accesses SessionContext at request time
  private val markVideoWatchedFlowLayer: ZLayer[
    VideoRepository[Task] & SessionContext & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, MarkVideoWatchedCommand, NextStep]
  ] = ZLayer.fromFunction:
    (videoRepo: VideoRepository[Task], ctx: SessionContext, eventHandler: EventHandler[Task, Event]) =>
      import zio.interop.catz.given
      new Flow[Task, MarkVideoWatchedCommand]:
        type Result = NextStep

        def execute(command: MarkVideoWatchedCommand): Task[NextStep] =
          for
            session <- ctx.getOrFail
            handler = MarkVideoWatchedHandler.withSession(videoRepo, session, nextPhaseAfterVideo)
            opResult <- handler.handle(command)
            result <- ZIO.fromEither(
              whitelabel.captal.core.Op.run(opResult).left.map(Flow.HandlerError(_)))
            (events, value) = result
            _ <- eventHandler.handle(events)
          yield value

  private val appLayers: ZLayer[Any, Throwable, FullEnv] = ZLayer.make[FullEnv](
    SessionContext.make,
    dataSourceLayer,
    quillLayer,
    sessionServiceLayer,
    localeServiceLayer,
    surveyRepoLayer,
    userRepoLayer,
    videoRepoLayer,
    eventHandlerLayer,
    answerEmailFlowLayer,
    answerProfilingFlowLayer,
    answerLocationFlowLayer,
    nextSurveyFlowLayer,
    nextVideoFlowLayer,
    markVideoWatchedFlowLayer
  )

  private def logRoutes: ZIO[ServerSettings, Nothing, Unit] =
    for
      settings <- ZIO.service[ServerSettings]
      binding    = settings.config.address
      routeInfos = endpoints(settings.devEndpoints).map: e =>
        val method = e.endpoint.method.map(_.method).getOrElse("*")
        val path = e.endpoint.showPathTemplate()
        s"  $method $path"
      _ <- ZIO.logInfo(s"Server starting on ${binding.getHostString}:${binding.getPort}")
      _ <- ZIO.logInfo(s"Dev mode: ${settings.devMode}, Dev endpoints: ${settings.devEndpoints}")
      _ <- ZIO.logInfo(s"Mounted routes:\n${routeInfos.mkString("\n")}")
    yield ()

  private val serverConfigFromSettings: ZLayer[ServerSettings, Nothing, Server.Config] = ZLayer
    .fromFunction((s: ServerSettings) => s.config)

  override val run: ZIO[Any, Throwable, Nothing] = ZIO
    .serviceWithZIO[ServerSettings]: settings =>
      logRoutes *> Server.serve(routes(settings.devMode, settings.devEndpoints))
    .provide(serverSettingsLayer, serverConfigFromSettings, Server.live, appLayers)
end Main
