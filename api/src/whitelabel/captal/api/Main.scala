package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.stringToPath
import sttp.tapir.ztapir.{RichZServerEndpoint, ZServerEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{
  Event,
  EventHandler,
  FallbackPhase,
  Flow,
  NextStep,
  Phase
}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository, VideoRepository}
import whitelabel.captal.infra.eventhandlers.{
  AnswerPersistenceHandler,
  DbEventHandler,
  EventLogHandler,
  SessionPhaseHandler,
  SessionSurveyHandler,
  SessionVideoHandler,
  SurveyProgressHandler,
  TransactionalEventHandler,
  UnifiAuthorizationHandler,
  UserPersistenceHandler
}
import whitelabel.captal.infra.provision.ProvisionService
import whitelabel.captal.infra.repositories.{
  SurveyRepositoryQuill,
  UserRepositoryQuill,
  VideoRepositoryQuill
}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.services.{LocaleService, LocaleServiceQuill, LocationService}
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import whitelabel.captal.infra.{RqliteDataSource, UnifiAccess}
import zio.*
import zio.http.*
import zio.interop.catz.*

object Main extends ZIOAppDefault:

  // ─── Configuration ───────────────────────────────────────────────────────────

  private case class ServerSettings(
      config: Server.Config,
      devMode: Boolean,
      devEndpoints: Boolean,
      locationSlug: Option[String],
      provisionDir: Option[String],
      sharedDir: Option[String],
      unifiProxyUrl: Option[String])

  private val serverSettingsLayer: ZLayer[Any, Throwable, ServerSettings] = ZLayer.fromZIO:
    ZIO.attempt:
      val c = ConfigFactory.load()
      ServerSettings(
        config = Server.Config.default.binding(c.getString("server.host"), c.getInt("server.port")),
        devMode = c.getBoolean("server.dev-mode"),
        devEndpoints = c.getBoolean("server.dev-endpoints"),
        locationSlug = Option(c.getString("location.slug")).filter(_.nonEmpty),
        provisionDir = Option(c.getString("provision.dir")).filter(_.nonEmpty),
        sharedDir = Option(c.getString("shared.dir")).filter(_.nonEmpty),
        unifiProxyUrl = Option(c.getString("unifi.proxy-url")).filter(_.nonEmpty)
      )

  // ─── Phase pipeline ───────────────────────────────────────────────────────────
  // Two kinds of transitions:
  //   - `nextAfterX: NextStep` — emitted on success by Answer*/MarkVideoWatched
  //     handlers; also serialized to the client as the response body.
  //   - `fallbackFromX: FallbackPhase` — destination for Provide* handlers when
  //     no resource (survey/video) is available for the current location, so
  //     the user falls back to the next stop in the pipeline.

  private val nextAfterIdentification: NextStep = NextStep(Phase.AdvertiserVideo)
  private val nextAfterVideo: NextStep = NextStep(Phase.AdvertiserVideoSurvey)
  private val nextAfterAdvertiserSurvey: NextStep = NextStep(Phase.Ready)

  private val fallbackFromIdentification: FallbackPhase = FallbackPhase(Phase.AdvertiserVideo)
  private val fallbackFromVideo: FallbackPhase = FallbackPhase(Phase.Ready)
  private val fallbackFromAdvertiserSurvey: FallbackPhase = FallbackPhase(Phase.Ready)

  // ─── Location-aware layers ────────────────────────────────────────────────────

  private def resolveLocationId: ZIO[ServerSettings & LocationService, Throwable, Option[String]] =
    ZIO.serviceWithZIO[ServerSettings]: settings =>
      settings.locationSlug match
        case None =>
          ZIO.succeed(None)
        case Some(slug) =>
          LocationService
            .resolveSlug(slug)
            .tapError(e => ZIO.logError(s"Configuration error: ${e.getMessage}"))
            .tap(id => ZIO.logInfo(s"Resolved location slug '$slug' -> $id"))
            .map(Some(_))

  private val sessionServiceLayer
      : ZLayer[QuillSqlite & LocationService & ServerSettings, Throwable, SessionService] = ZLayer
    .fromZIO:
      for
        quill      <- ZIO.service[QuillSqlite]
        locationId <- resolveLocationId
      yield SessionService(quill, locationId)

  private val localeServiceLayer
      : ZLayer[QuillSqlite & LocationService & ServerSettings, Throwable, LocaleService] = ZLayer
    .fromZIO:
      for
        quill      <- ZIO.service[QuillSqlite]
        locationId <- resolveLocationId
      yield LocaleServiceQuill(quill, locationId)

  // ─── Event handling & flows ───────────────────────────────────────────────────

  private val eventHandlerLayer: ZLayer[
    QuillSqlite & SessionContext & CurrentLocation & SessionService & Client,
    Nothing,
    EventHandler[Task, Event]] = ZLayer.fromFunction:
    (
        quill: QuillSqlite,
        ctx: SessionContext,
        currentLocation: CurrentLocation,
        sessionService: SessionService,
        client: Client) =>
      val dbHandler = EventLogHandler(ctx)
        .andThen(AnswerPersistenceHandler(ctx))
        .andThen(UserPersistenceHandler(ctx))
        .andThen(SessionPhaseHandler(ctx, nextAfterIdentification.phase, nextAfterVideo.phase))
        .andThen(SessionSurveyHandler(ctx))
        .andThen(SessionVideoHandler(ctx))
        .andThen(SurveyProgressHandler())
      val transactional = TransactionalEventHandler(dbHandler, quill)
      val unifiAuth = UnifiAuthorizationHandler(currentLocation.unifi, ctx, sessionService, client)
      // unifiAuth corre POST-commit; cualquier fallo HTTP no impacta el chain transaccional.
      transactional.andThen(unifiAuth)

  private val answerEmailFlowLayer: ZLayer[SurveyRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, AnswerEmailCommand, NextStep]] =
    ZLayer.fromFunction:
      (surveyRepo: SurveyRepository[Task], eventHandler: EventHandler[Task, Event]) =>
        Flow(AnswerEmailHandler(surveyRepo, nextAfterIdentification), eventHandler)

  private val answerProfilingFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerProfilingCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerProfilingHandler(surveyRepo, userRepo, nextAfterIdentification), eventHandler)

  private val answerLocationFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerLocationCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerLocationHandler(surveyRepo, userRepo, nextAfterIdentification), eventHandler)

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
        ProvideNextIdentificationSurveyHandler(surveyRepo, userRepo, fallbackFromIdentification),
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
      Flow(ProvideNextVideoHandler(videoRepo, userRepo, fallbackFromVideo), eventHandler)

  private val nextAdvertiserSurveyFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, ProvideNextAdvertiserSurveyCommand, ProvideNextAdvertiserSurveyHandler.Response]
  ] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(
        ProvideNextAdvertiserSurveyHandler(surveyRepo, userRepo, fallbackFromAdvertiserSurvey),
        eventHandler)

  private val answerAdvertiserFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerAdvertiserCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerAdvertiserHandler(surveyRepo, userRepo, nextAfterAdvertiserSurvey), eventHandler)

  private val finishFlowLayer: ZLayer[UserRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, FinishCommand, Unit]] = ZLayer
    .fromFunction: (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(FinishHandler(userRepo), eventHandler)

  private val markVideoWatchedFlowLayer: ZLayer[
    VideoRepository[Task] & SessionContext & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, MarkVideoWatchedCommand, NextStep]] = ZLayer.fromFunction:
    (
        videoRepo: VideoRepository[Task],
        ctx: SessionContext,
        eventHandler: EventHandler[Task, Event]) =>
      import zio.interop.catz.given
      new Flow[Task, MarkVideoWatchedCommand]:
        type Result = NextStep
        def execute(command: MarkVideoWatchedCommand): Task[NextStep] =
          for
            session <- ctx.getOrFail
            handler = MarkVideoWatchedHandler.withSession(videoRepo, session, nextAfterVideo)
            opResult <- handler.handle(command)
            result   <- ZIO.fromEither(
              whitelabel.captal.core.Op.run(opResult).left.map(Flow.HandlerError(_)))
            (events, value) = result
            _ <- eventHandler.handle(events)
          yield value

  // ─── Session cookie config (per-location naming/path) ─────────────────────────

  private val sessionCookieConfigLayer: ZLayer[ServerSettings, Nothing, SessionCookieConfig] =
    ZLayer.fromFunction((s: ServerSettings) => SessionCookieConfig.fromSlug(s.locationSlug))

  // ─── UniFi HTTP client (trust-all + optional proxy) ───────────────────────────

  private val unifiClientLayer: ZLayer[ServerSettings, Throwable, Client] = ZLayer
    .service[ServerSettings]
    .flatMap(env => UnifiAuthorizationHandler.trustAllClientLayer(env.get.unifiProxyUrl))

  // ─── Current location snapshot (for soft-validation of AP MAC, etc.) ──────────

  private val currentLocationLayer
      : ZLayer[ServerSettings & LocationService, Throwable, CurrentLocation] = ZLayer.fromZIO:
    for
      settings <- ZIO.service[ServerSettings]
      locSvc   <- ZIO.service[LocationService]
      cl       <-
        settings.locationSlug match
          case None =>
            ZIO.succeed(CurrentLocation.empty)
          case Some(s) =>
            locSvc
              .findBySlug(s)
              .someOrFail(LocationService.LocationNotFound(s))
              .map: row =>
                CurrentLocation(Some(row.id), Some(row.slug), row.apMac, UnifiAccess.fromRow(row))
    yield cl

  // ─── Routes ───────────────────────────────────────────────────────────────────

  type FullEnv =
    SessionContext & SessionService & LocaleService & QuillSqlite & SessionEndpoint &
      SessionCookieConfig & CurrentLocation & UserCookieConfig & UserLookup & SurveyRoutes &
      LocaleRoutes & VideoRoutes & AdvertiserSurveyRoutes & FinishRoutes &
      SurveyRoutes.AnswerEmailFlowType & SurveyRoutes.AnswerProfilingFlowType &
      SurveyRoutes.AnswerLocationFlowType & SurveyRoutes.NextSurveyFlowType &
      VideoRoutes.NextVideoFlowType & VideoRoutes.MarkVideoWatchedFlowType &
      AdvertiserSurveyRoutes.NextAdvertiserSurveyFlowType &
      AdvertiserSurveyRoutes.AnswerAdvertiserFlowType & FinishRoutes.FinishFlowType

  private def endpoints(
      devEndpoints: Boolean,
      locationSlug: Option[String],
      surveyRoutes: SurveyRoutes,
      localeRoutes: LocaleRoutes,
      videoRoutes: VideoRoutes,
      advertiserSurveyRoutes: AdvertiserSurveyRoutes,
      finishRoutes: FinishRoutes): List[ZServerEndpoint[FullEnv, Any]] =
    val base =
      HealthRoutes.routes.map(_.widen[FullEnv]) ++ surveyRoutes.routes.map(_.widen[FullEnv]) ++
        localeRoutes.routes.map(_.widen[FullEnv]) ++ videoRoutes.routes.map(_.widen[FullEnv]) ++
        advertiserSurveyRoutes.routes.map(_.widen[FullEnv]) ++
        finishRoutes.routes.map(_.widen[FullEnv])
    val withDev =
      if devEndpoints then
        base ++ localeRoutes.devRoutes.map(_.widen[FullEnv])
      else
        base
    locationSlug match
      case Some(slug) =>
        withDev.map(_.prependSecurityIn(slug))
      case None =>
        withDev
  end endpoints

  private def apiRoutes(
      devEndpoints: Boolean,
      locationSlug: Option[String],
      surveyRoutes: SurveyRoutes,
      localeRoutes: LocaleRoutes,
      videoRoutes: VideoRoutes,
      advertiserSurveyRoutes: AdvertiserSurveyRoutes,
      finishRoutes: FinishRoutes) = ZioHttpInterpreter().toHttp(
    endpoints(
      devEndpoints,
      locationSlug,
      surveyRoutes,
      localeRoutes,
      videoRoutes,
      advertiserSurveyRoutes,
      finishRoutes))

  private def loggingMiddleware[R]: Middleware[R] =
    new Middleware[R]:
      def apply[Env1 <: R, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] = routes.transform[
        Env1]: handler =>
        zio
          .http
          .Handler
          .fromFunctionZIO[Request]: request =>
            val startTime = java.lang.System.currentTimeMillis()
            handler
              .runZIO(request)
              .tap: response =>
                val duration = java.lang.System.currentTimeMillis() - startTime
                ZIO.logInfo(
                  s"${request.method} ${request.url.path} -> ${response
                      .status
                      .code} (${duration}ms)")
              .tapError: error =>
                val duration = java.lang.System.currentTimeMillis() - startTime
                ZIO.logError(
                  s"${request.method} ${request.url.path} -> ERROR (${duration}ms): $error")

  private def devStaticRoutes: Routes[Any, Response] = Routes(
    Method.GET / "assets" / "styles.css" ->
      zio.http.Handler.fromFile(java.io.File("client/assets/styles.css")),
    Method.GET / "brand-icon.svg" ->
      zio.http.Handler.fromFile(java.io.File("client/assets/brand-icon.svg")),
    Method.GET / "out" / "client" / "fastLinkJS.dest" / "main.js" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fastLinkJS.dest/main.js")),
    Method.GET / "out" / "client" / "fastLinkJS.dest" / "main.js.map" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fastLinkJS.dest/main.js.map")),
    Method.GET / "out" / "client" / "fullLinkJS.dest" / "main.js" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fullLinkJS.dest/main.js")),
    Method.GET / "out" / "client" / "fullLinkJS.dest" / "main.js.map" ->
      zio.http.Handler.fromFile(java.io.File("out/client/fullLinkJS.dest/main.js.map"))
  ).handleError(e => Response.internalServerError(e.getMessage))

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
      Method.GET / "ready"    -> serveIndex
    )
  end spaCatchAllRoutes

  private def routes(
      devMode: Boolean,
      devEndpoints: Boolean,
      locationSlug: Option[String],
      surveyRoutes: SurveyRoutes,
      localeRoutes: LocaleRoutes,
      videoRoutes: VideoRoutes,
      advertiserSurveyRoutes: AdvertiserSurveyRoutes,
      finishRoutes: FinishRoutes): Routes[FullEnv, Response] =
    val api = apiRoutes(
      devEndpoints,
      locationSlug,
      surveyRoutes,
      localeRoutes,
      videoRoutes,
      advertiserSurveyRoutes,
      finishRoutes)
    val baseRoutes =
      if devMode then
        devStaticRoutes ++ api ++ spaCatchAllRoutes
      else
        api
    baseRoutes @@ loggingMiddleware
  end routes

  // ─── App layer composition ────────────────────────────────────────────────────

  /** LocationService layer that runs location provisioning first, ensuring the location exists in
    * the DB before any slug resolution. Shared provisioning (surveys + advertisers) is handled
    * exclusively by the ephemeral task launched from `captal shared push`.
    */
  private val locationServiceWithProvisioning
      : ZLayer[ServerSettings & QuillSqlite, Throwable, LocationService] = ZLayer.fromZIO:
    for
      settings <- ZIO.service[ServerSettings]
      quill    <- ZIO.service[QuillSqlite]
      _        <-
        (settings.provisionDir, settings.locationSlug) match
          case (Some(dir), Some(slug)) =>
            ProvisionService.run(quill, dir, slug)
          case (Some(_), None) =>
            ZIO.fail(new RuntimeException("PROVISION_DIR set but LOCATION_SLUG is missing"))
          case _ =>
            ZIO.logInfo("No PROVISION_DIR configured, skipping provisioning")
    yield LocationService(quill)

  private val appLayers: ZLayer[ServerSettings, Throwable, FullEnv & Server.Config] = ZLayer
    .makeSome[ServerSettings, FullEnv & Server.Config](
      SessionContext.make,
      RqliteDataSource.layer,
      Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase),
      locationServiceWithProvisioning,
      sessionServiceLayer,
      localeServiceLayer,
      sessionCookieConfigLayer,
      currentLocationLayer,
      UserCookieConfig.layer,
      UserLookup.layer,
      SessionEndpoint.layer,
      SurveyRoutes.layer,
      LocaleRoutes.layer,
      VideoRoutes.layer,
      AdvertiserSurveyRoutes.layer,
      FinishRoutes.layer,
      SurveyRepositoryQuill.layer,
      UserRepositoryQuill.layer,
      VideoRepositoryQuill.layer,
      unifiClientLayer,
      eventHandlerLayer,
      answerEmailFlowLayer,
      answerProfilingFlowLayer,
      answerLocationFlowLayer,
      nextSurveyFlowLayer,
      nextVideoFlowLayer,
      markVideoWatchedFlowLayer,
      nextAdvertiserSurveyFlowLayer,
      answerAdvertiserFlowLayer,
      finishFlowLayer,
      ZLayer.fromFunction((s: ServerSettings) => s.config)
    )

  // ─── Startup ──────────────────────────────────────────────────────────────────

  override val run: ZIO[Any, Throwable, Nothing] =
    val program =
      for
        settings               <- ZIO.service[ServerSettings]
        surveyRoutes           <- ZIO.service[SurveyRoutes]
        localeRoutes           <- ZIO.service[LocaleRoutes]
        videoRoutes            <- ZIO.service[VideoRoutes]
        advertiserSurveyRoutes <- ZIO.service[AdvertiserSurveyRoutes]
        finishRoutes           <- ZIO.service[FinishRoutes]
        cookieConfig           <- ZIO.service[SessionCookieConfig]
        ep = endpoints(
          settings.devEndpoints,
          settings.locationSlug,
          surveyRoutes,
          localeRoutes,
          videoRoutes,
          advertiserSurveyRoutes,
          finishRoutes)
        routeInfos = ep.map: e =>
          s"  ${e.endpoint.method.map(_.method).getOrElse("*")} ${e.endpoint.showPathTemplate()}"
        _ <- ZIO.logInfo(
          s"Server starting on ${settings.config.address.getHostString}:${settings
              .config
              .address
              .getPort}")
        _ <- ZIO.logInfo(s"Dev mode: ${settings.devMode}, Dev endpoints: ${settings.devEndpoints}")
        _ <- ZIO.logInfo(s"Session cookie: ${cookieConfig.name} (path=${cookieConfig.path})")
        _ <- ZIO.logInfo(s"Mounted routes:\n${routeInfos.mkString("\n")}")
        result <- Server.serve(
          routes(
            settings.devMode,
            settings.devEndpoints,
            settings.locationSlug,
            surveyRoutes,
            localeRoutes,
            videoRoutes,
            advertiserSurveyRoutes,
            finishRoutes))
      yield result
    program.provide(serverSettingsLayer, Server.live, appLayers)
  end run

end Main
