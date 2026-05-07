package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.stringToPath
import sttp.tapir.ztapir.{RichZServerEndpoint, ZServerEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository, VideoRepository}
import whitelabel.captal.infra.RqliteDataSource
import whitelabel.captal.infra.provision.ProvisionService
import whitelabel.captal.infra.services.{LocaleService, LocaleServiceQuill, LocationService}
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
import whitelabel.captal.infra.repositories.{
  SurveyRepositoryQuill,
  UserRepositoryQuill,
  VideoRepositoryQuill
}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.session.{SessionContext, SessionService}
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
      sharedDir: Option[String])

  private val serverSettingsLayer: ZLayer[Any, Throwable, ServerSettings] = ZLayer.fromZIO:
    ZIO.attempt:
      val c = ConfigFactory.load()
      ServerSettings(
        config = Server.Config.default.binding(c.getString("server.host"), c.getInt("server.port")),
        devMode = c.getBoolean("server.dev-mode"),
        devEndpoints = c.getBoolean("server.dev-endpoints"),
        locationSlug = Option(c.getString("location.slug")).filter(_.nonEmpty),
        provisionDir = Option(c.getString("provision.dir")).filter(_.nonEmpty),
        sharedDir = Option(c.getString("shared.dir")).filter(_.nonEmpty)
      )

  // ─── Phase transitions ────────────────────────────────────────────────────────

  private val nextPhaseAfterIdentificationQuestion: Phase = Phase.AdvertiserVideo
  private val nextStepAfterIdentificationQuestion: NextStep = NextStep(
    nextPhaseAfterIdentificationQuestion)
  private val nextPhaseAfterVideo: Phase = Phase.AdvertiserVideoSurvey

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

  private val eventHandlerLayer
      : ZLayer[QuillSqlite & SessionContext, Nothing, EventHandler[Task, Event]] = ZLayer
    .fromFunction: (quill: QuillSqlite, ctx: SessionContext) =>
      val dbHandler = EventLogHandler(ctx)
        .andThen(AnswerPersistenceHandler(ctx))
        .andThen(UserPersistenceHandler(ctx))
        .andThen(
          SessionPhaseHandler(ctx, nextPhaseAfterIdentificationQuestion, nextPhaseAfterVideo))
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
      Flow(ProvideNextVideoHandler(videoRepo, userRepo, Phase.Ready), eventHandler)

  private val nextAdvertiserSurveyFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, ProvideNextAdvertiserSurveyCommand, ProvideNextAdvertiserSurveyHandler.Response]
  ] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(ProvideNextAdvertiserSurveyHandler(surveyRepo, userRepo, Phase.Ready), eventHandler)

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
            handler = MarkVideoWatchedHandler.withSession(videoRepo, session, nextPhaseAfterVideo)
            opResult <- handler.handle(command)
            result   <- ZIO.fromEither(
              whitelabel.captal.core.Op.run(opResult).left.map(Flow.HandlerError(_)))
            (events, value) = result
            _ <- eventHandler.handle(events)
          yield value

  // ─── Session cookie config (per-location naming/path) ─────────────────────────

  private val sessionCookieConfigLayer: ZLayer[ServerSettings, Nothing, SessionCookieConfig] =
    ZLayer.fromFunction((s: ServerSettings) => SessionCookieConfig.fromSlug(s.locationSlug))

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
              .map(row => CurrentLocation(Some(row.id), Some(row.slug), row.apMac))
    yield cl

  // ─── Routes ───────────────────────────────────────────────────────────────────

  type FullEnv =
    SessionContext & SessionService & LocaleService & QuillSqlite & SessionEndpoint &
      SessionCookieConfig & CurrentLocation & SurveyRoutes & LocaleRoutes & VideoRoutes &
      AdvertiserSurveyRoutes & SurveyRoutes.AnswerEmailFlowType &
      SurveyRoutes.AnswerProfilingFlowType & SurveyRoutes.AnswerLocationFlowType &
      SurveyRoutes.NextSurveyFlowType & VideoRoutes.NextVideoFlowType &
      VideoRoutes.MarkVideoWatchedFlowType & AdvertiserSurveyRoutes.NextAdvertiserSurveyFlowType &
      AdvertiserSurveyRoutes.AnswerAdvertiserFlowType

  private def endpoints(
      devEndpoints: Boolean,
      locationSlug: Option[String],
      surveyRoutes: SurveyRoutes,
      localeRoutes: LocaleRoutes,
      videoRoutes: VideoRoutes,
      advertiserSurveyRoutes: AdvertiserSurveyRoutes): List[ZServerEndpoint[FullEnv, Any]] =
    val base =
      HealthRoutes.routes.map(_.widen[FullEnv]) ++ surveyRoutes.routes.map(_.widen[FullEnv]) ++
        localeRoutes.routes.map(_.widen[FullEnv]) ++ videoRoutes.routes.map(_.widen[FullEnv]) ++
        advertiserSurveyRoutes.routes.map(_.widen[FullEnv])
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
      advertiserSurveyRoutes: AdvertiserSurveyRoutes) = ZioHttpInterpreter().toHttp(
    endpoints(
      devEndpoints,
      locationSlug,
      surveyRoutes,
      localeRoutes,
      videoRoutes,
      advertiserSurveyRoutes))

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
      advertiserSurveyRoutes: AdvertiserSurveyRoutes): Routes[FullEnv, Response] =
    val api = apiRoutes(
      devEndpoints,
      locationSlug,
      surveyRoutes,
      localeRoutes,
      videoRoutes,
      advertiserSurveyRoutes)
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
      SessionEndpoint.layer,
      SurveyRoutes.layer,
      LocaleRoutes.layer,
      VideoRoutes.layer,
      AdvertiserSurveyRoutes.layer,
      SurveyRepositoryQuill.layer,
      UserRepositoryQuill.layer,
      VideoRepositoryQuill.layer,
      eventHandlerLayer,
      answerEmailFlowLayer,
      answerProfilingFlowLayer,
      answerLocationFlowLayer,
      nextSurveyFlowLayer,
      nextVideoFlowLayer,
      markVideoWatchedFlowLayer,
      nextAdvertiserSurveyFlowLayer,
      answerAdvertiserFlowLayer,
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
        cookieConfig           <- ZIO.service[SessionCookieConfig]
        ep = endpoints(
          settings.devEndpoints,
          settings.locationSlug,
          surveyRoutes,
          localeRoutes,
          videoRoutes,
          advertiserSurveyRoutes)
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
            advertiserSurveyRoutes))
      yield result
    program.provide(serverSettingsLayer, Server.live, appLayers)
  end run

end Main
