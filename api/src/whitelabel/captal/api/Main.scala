package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.{RichZServerEndpoint, ZServerEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository, VideoRepository}
import whitelabel.captal.infra.RqliteDataSource
import whitelabel.captal.infra.provision.ProvisionService
import whitelabel.captal.infra.services.LocationService
import whitelabel.captal.infra.eventhandlers.{
  AnswerPersistenceHandler,
  DbEventHandler,
  EventLogHandler,
  SessionPhaseHandler,
  SessionSurveyHandler,
  SessionVideoHandler,
  SurveyProgressHandler,
  TransactionalEventHandler,
  UserPersistenceHandler
}
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
  // After watching a video, transition to AdvertiserVideoSurvey to answer advertiser questions
  private val nextPhaseAfterVideo: Phase = Phase.AdvertiserVideoSurvey
  type FullEnv =
    SessionContext & SessionService & LocaleService & QuillSqlite &
      SurveyRoutes.AnswerEmailFlowType &
      SurveyRoutes.AnswerProfilingFlowType & SurveyRoutes.AnswerLocationFlowType &
      SurveyRoutes.NextSurveyFlowType & VideoRoutes.NextVideoFlowType &
      VideoRoutes.MarkVideoWatchedFlowType & AdvertiserSurveyRoutes.NextAdvertiserSurveyFlowType &
      AdvertiserSurveyRoutes.AnswerAdvertiserFlowType

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

  private val advertiserSurveyEndpoints: List[ZServerEndpoint[FullEnv, Any]] = AdvertiserSurveyRoutes
    .routes
    .map(_.widen[FullEnv])

  private val devOnlyEndpoints: List[ZServerEndpoint[FullEnv, Any]] = LocaleRoutes
    .devRoutes
    .map(_.widen[FullEnv])

  private def endpoints(devEndpoints: Boolean): List[ZServerEndpoint[FullEnv, Any]] =
    val base = healthEndpoints ++ surveyEndpoints ++ localeEndpoints ++ videoEndpoints ++ advertiserSurveyEndpoints
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
      Method.GET / "survey"   -> serveIndex,
      Method.GET / "ready"    -> serveIndex)

  private def routes(devMode: Boolean, devEndpoints: Boolean): Routes[FullEnv, Response] =
    val baseRoutes =
      if devMode then devStaticRoutes ++ apiRoutes(devEndpoints) ++ spaCatchAllRoutes
      else apiRoutes(devEndpoints)
    baseRoutes @@ loggingMiddleware

  private case class ServerSettings(
      config: Server.Config,
      devMode: Boolean,
      devEndpoints: Boolean,
      locationSlug: Option[String],
      provisionDir: Option[String])

  private val serverSettingsLayer: ZLayer[Any, Throwable, ServerSettings] = ZLayer.fromZIO:
    ZIO.attempt:
      val c = ConfigFactory.load()
      val config = Server
        .Config
        .default
        .binding(c.getString("server.host"), c.getInt("server.port"))
      val devMode = c.getBoolean("server.dev-mode")
      val devEndpoints = c.getBoolean("server.dev-endpoints")
      val locationSlug = Option(c.getString("location.slug")).filter(_.nonEmpty)
      val provisionDir = Option(c.getString("provision.dir")).filter(_.nonEmpty)
      ServerSettings(config, devMode, devEndpoints, locationSlug, provisionDir)

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  private val dataSourceLayer = RqliteDataSource.layer

  private val locationServiceLayer = LocationService.layer

  /** Resolve location and create a SessionService layer with the resolved locationId. */
  private def sessionServiceLayerWithLocation(
      settings: ServerSettings): ZLayer[QuillSqlite & LocationService, Throwable, SessionService] =
    ZLayer.fromZIO:
      for
        quill <- ZIO.service[QuillSqlite]
        locationId <- settings.locationSlug match
          case None => ZIO.succeed(None)
          case Some(slug) =>
            LocationService
              .resolveSlug(slug)
              .tapError(e => ZIO.logError(s"Configuration error: ${e.getMessage}"))
              .tap(id => ZIO.logInfo(s"Resolved location slug '$slug' -> $id"))
              .map(Some(_))
      yield SessionService.apply(quill, locationId)

  private val localeServiceLayer = LocaleService.layer

  private val surveyRepoLayer = SurveyRepositoryQuill.layer

  private val userRepoLayer = UserRepositoryQuill.layer

  private val videoRepoLayer = VideoRepositoryQuill.layer

  private val eventHandlerLayer
      : ZLayer[QuillSqlite & SessionContext, Nothing, EventHandler[Task, Event]] = ZLayer
    .fromFunction: (quill: QuillSqlite, ctx: SessionContext) =>
      val dbHandler = EventLogHandler(ctx)
        .andThen(AnswerPersistenceHandler(ctx))
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
      // When no video is available, go to Ready (not AdvertiserVideoSurvey)
      Flow(ProvideNextVideoHandler(videoRepo, userRepo, Phase.Ready), eventHandler)

  private val nextAdvertiserSurveyFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[
      Task,
      ProvideNextAdvertiserSurveyCommand,
      ProvideNextAdvertiserSurveyHandler.Response]
  ] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(
        ProvideNextAdvertiserSurveyHandler(surveyRepo, userRepo, Phase.Ready),
        eventHandler)

  private val answerAdvertiserFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerAdvertiserCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(
        AnswerAdvertiserHandler(surveyRepo, userRepo, NextStep(Phase.AdvertiserVideoSurvey)),
        eventHandler)

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

  private def appLayers(settings: ServerSettings): ZLayer[Any, Throwable, FullEnv] = ZLayer.make[
    FullEnv](
    SessionContext.make,
    dataSourceLayer,
    quillLayer,
    locationServiceLayer,
    sessionServiceLayerWithLocation(settings),
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
    markVideoWatchedFlowLayer,
    nextAdvertiserSurveyFlowLayer,
    answerAdvertiserFlowLayer
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

  /** Run provisioning if PROVISION_DIR is set. */
  private def runProvisioning: ZIO[ServerSettings & QuillSqlite, Throwable, Unit] =
    for
      settings <- ZIO.service[ServerSettings]
      _ <- (settings.provisionDir, settings.locationSlug) match
        case (Some(dir), Some(slug)) =>
          ZIO.serviceWithZIO[QuillSqlite](quill => ProvisionService.run(quill, dir, slug))
        case (Some(_), None) =>
          ZIO.fail(new RuntimeException("PROVISION_DIR set but LOCATION_SLUG is missing"))
        case _ =>
          ZIO.logInfo("No PROVISION_DIR configured, skipping provisioning")
    yield ()

  override val run: ZIO[Any, Throwable, Nothing] = ZIO
    .serviceWithZIO[ServerSettings]: settings =>
      runProvisioning *>
        logRoutes *>
        Server.serve(routes(settings.devMode, settings.devEndpoints))
    .provideSome[ServerSettings](
      serverConfigFromSettings,
      Server.live,
      ZLayer.fromFunction((s: ServerSettings) => appLayers(s)).flatten)
    .provide(serverSettingsLayer)
end Main
