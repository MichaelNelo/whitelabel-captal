package whitelabel.captal.api

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
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
import whitelabel.captal.platform.eventhandlers.*
import whitelabel.captal.platform.repositories.{
  SurveyRepositoryQuill,
  UserRepositoryQuill,
  VideoRepositoryQuill
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.services.LocaleService
import whitelabel.captal.platform.session.{JobQueue, JobRunner, SessionContext, SessionService}
import whitelabel.captal.platform.{RqliteDataSource, UnifiAccess}
import zio.*
import zio.interop.catz.*

object TestLayers:
  // ─── Phase pipeline (mirrors Main.scala) ──────────────────────────────────────

  private val nextAfterIdentification: NextStep = NextStep(Phase.AdvertiserVideo)
  private val nextAfterVideo: NextStep = NextStep(Phase.AdvertiserVideoSurvey)
  private val nextAfterAdvertiserSurvey: NextStep = NextStep(Phase.Ready)

  private val fallbackFromIdentification: FallbackPhase = FallbackPhase(Phase.AdvertiserVideo)
  private val fallbackFromVideo: FallbackPhase = FallbackPhase(Phase.Ready)
  private val fallbackFromAdvertiserSurvey: FallbackPhase = FallbackPhase(Phase.Ready)

  private val testConfig = ConfigFactory.load("test.conf")

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  private val dataSourceLayer: ZLayer[Any, Throwable, DataSource] = ZLayer.fromZIO:
    ZIO.attempt:
      val url = testConfig.getString("database.jdbcUrl")
      RqliteDataSource.create(url)

  private val sessionServiceLayer = SessionService.layer

  private val localeServiceLayer = LocaleService.layer

  private val surveyRepoLayer = SurveyRepositoryQuill.layer

  private val userRepoLayer = UserRepositoryQuill.layer

  private val videoRepoLayer = VideoRepositoryQuill.layer

  private val eventHandlerLayer: ZLayer[
    QuillSqlite & SessionContext & SessionService & CurrentLocation & TestUnifiClient & JobQueue,
    Nothing,
    EventHandler[Task, Event]] = ZLayer.fromFunction:
    (
        quill: QuillSqlite,
        ctx: SessionContext,
        sessionService: SessionService,
        currentLocation: CurrentLocation,
        unifiClient: TestUnifiClient,
        jobQueue: JobQueue) =>
      val dbHandler = EventLogHandler(ctx)
        .andThen(AnswerPersistenceHandler(ctx))
        .andThen(UserPersistenceHandler(ctx))
        .andThen(SessionPhaseHandler(ctx, nextAfterIdentification.phase, nextAfterVideo.phase))
        .andThen(SessionSurveyHandler(ctx))
        .andThen(SessionVideoHandler(ctx))
        .andThen(SessionExpirationHandler(ctx))
        .andThen(SurveyProgressHandler())
      val transactional = TransactionalEventHandler(dbHandler, quill)
      // The production handler, with its HTTP transport swapped for TestUnifiClient.
      val unifiAccess = UnifiAccessHandler(
        currentLocation.unifi,
        ctx,
        sessionService,
        unifiClient,
        jobQueue)
      transactional.andThen(unifiAccess)

  private val expireAccessFlowLayer: ZLayer[UserRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, ExpireAccessCommand, Unit]] = ZLayer
    .fromFunction: (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(ExpireAccessHandler(userRepo), eventHandler)

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
      ProvideNextIdentificationSurveyCommand,
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
          fallbackFromIdentification,
          FallbackPhase(Phase.Ready)),
        eventHandler)

  private val nextVideoFlowLayer: ZLayer[
    VideoRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, ProvideNextVideoCommand, ProvideNextVideoHandler.Response]] = ZLayer
    .fromFunction:
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

  private val finishFlowLayer: ZLayer[UserRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, FinishCommand, Unit]] = ZLayer
    .fromFunction: (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(FinishHandler(userRepo), eventHandler)

  val quill: ZLayer[Any, Throwable, QuillSqlite] = dataSourceLayer >>> quillLayer

  // No slug in tests → SessionCookieConfig produces name="captal_session", path="/"
  private val sessionCookieConfigLayer: ULayer[SessionCookieConfig] = ZLayer.succeed(
    SessionCookieConfig.fromSlug(None))

  /** Tests have no location row, but they do need UniFi config so `UnifiAccessHandler` walks the
    * real path. `TestUnifiClient` intercepts the transport, so these values never reach the
    * network.
    *
    * `defaultDurationMinutes = 0` makes `expiresAt == occurredAt`: the expiration is already due at
    * the very instant access is granted, so the scheduler processes it as soon as it starts. This
    * avoids advancing the `TestClock`, whose plumbing conflicts with `provideShared`, and does not
    * affect the other tests because the test clock is frozen and `/api/status` checks with
    * `isBefore(now)`.
    */
  private val currentLocationLayer: ULayer[CurrentLocation] = ZLayer.succeed(
    CurrentLocation(
      None,
      None,
      None,
      Some(
        UnifiAccess(
          host = "unifi.test",
          apiToken = "test-token",
          port = 443,
          siteId = "test-site",
          defaultDurationMinutes = 0))))

  type TestEnv =
    SessionContext & SessionService & LocaleService & SurveyRoutes.AnswerEmailFlowType &
      SurveyRoutes.AnswerProfilingFlowType & SurveyRoutes.AnswerLocationFlowType &
      SurveyRoutes.NextSurveyFlowType & VideoRoutes.NextVideoFlowType &
      VideoRoutes.MarkVideoWatchedFlowType & AdvertiserSurveyRoutes.NextAdvertiserSurveyFlowType &
      AdvertiserSurveyRoutes.AnswerAdvertiserFlowType & FinishRoutes.FinishFlowType & QuillSqlite &
      SessionCookieConfig & CurrentLocation & UserCookieConfig & UserLookup & SurveyRoutes &
      LocaleRoutes & VideoRoutes & AdvertiserSurveyRoutes & FinishRoutes & TestUnifiClient &
      JobQueue & JobRunner.ExpireFlow & JobRunner

  val testEnv: ZLayer[Any, Throwable, TestEnv] = ZLayer.make[TestEnv](
    SessionContext.make,
    dataSourceLayer,
    quillLayer,
    sessionServiceLayer,
    localeServiceLayer,
    surveyRepoLayer,
    userRepoLayer,
    videoRepoLayer,
    TestUnifiClient.layer,
    JobQueue.layer,
    expireAccessFlowLayer,
    JobRunner.layer,
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
    sessionCookieConfigLayer,
    currentLocationLayer,
    UserCookieConfig.layer,
    UserLookup.layer,
    SurveyRoutes.layer,
    LocaleRoutes.layer,
    VideoRoutes.layer,
    AdvertiserSurveyRoutes.layer,
    FinishRoutes.layer
  )
end TestLayers
