package whitelabel.captal.api

import whitelabel.captal.api.Phases.*
import whitelabel.captal.api.session.CurrentLocation
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow, NextStep}
import whitelabel.captal.core.infrastructure.{
  SessionData,
  SurveyRepository,
  UserRepository,
  VideoRepository
}
import whitelabel.captal.platform.UnifiClient
import whitelabel.captal.platform.eventhandlers.{
  AnswerPersistenceHandler,
  EventLogHandler,
  PlaybackHandler,
  SessionExpirationHandler,
  SessionPhaseHandler,
  SessionSurveyHandler,
  SessionVideoHandler,
  SurveyProgressHandler,
  TransactionalEventHandler,
  UnifiAccessHandler,
  UserPersistenceHandler
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.services.LocationService
import whitelabel.captal.platform.session.{JobQueue, SessionContext, SessionService}
import zio.*
import zio.interop.catz.*

/** One layer per command the portal can execute, plus the handler chain they all write through.
  *
  * Every entry is a `Flow` pairing one command handler with the event handler; what varies is which
  * repositories the handler reads and which `Phases` value it emits.
  */
private[api] object Flows:

  val eventHandlerLayer: ZLayer[
    QuillSqlite & SessionContext & CurrentLocation & SessionService & UnifiClient & JobQueue,
    Nothing,
    EventHandler[Task, Event]] = ZLayer.fromFunction:
    (
        quill: QuillSqlite,
        ctx: SessionContext,
        currentLocation: CurrentLocation,
        sessionService: SessionService,
        unifiClient: UnifiClient,
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
      val unifiAccess = UnifiAccessHandler(
        currentLocation.unifi,
        unifiEnabled(quill, currentLocation),
        ctx,
        sessionService,
        unifiClient,
        jobQueue)
      // Both run after the commit: a UniFi call failing must not undo the chain, and a playback
      // has to see its own insert, which a transaction defers.
      transactional.andThen(PlaybackHandler(quill)).andThen(unifiAccess)

  /** The served location's UniFi switch, read from its row on every call. Without a location (dev,
    * standalone) there is no switch, and the UniFi config alone decides.
    */
  private[api] def unifiEnabled(quill: QuillSqlite, location: CurrentLocation): Task[Boolean] =
    location.id match
      case Some(id) =>
        LocationService(quill).unifiEnabled(id)
      case None =>
        ZIO.succeed(true)

  val answerEmailFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerEmailCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerEmailHandler(surveyRepo, userRepo, nextAfterIdentification), eventHandler)

  val answerProfilingFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerProfilingCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerProfilingHandler(surveyRepo, userRepo, nextAfterIdentification), eventHandler)

  val answerLocationFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerLocationCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerLocationHandler(surveyRepo, userRepo, nextAfterIdentification), eventHandler)

  val nextSurveyFlowLayer: ZLayer[
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
          unidentifiableFallback),
        eventHandler)

  val nextVideoFlowLayer: ZLayer[
    VideoRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, ProvideNextVideoCommand, ProvideNextVideoHandler.Response]] = ZLayer
    .fromFunction:
      (
          videoRepo: VideoRepository[Task],
          userRepo: UserRepository[Task],
          eventHandler: EventHandler[Task, Event]) =>
        Flow(ProvideNextVideoHandler(videoRepo, userRepo, fallbackFromVideo), eventHandler)

  val nextAdvertiserSurveyFlowLayer: ZLayer[
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

  val answerAdvertiserFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerAdvertiserCommand, NextStep]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerAdvertiserHandler(surveyRepo, userRepo, nextAfterAdvertiserSurvey), eventHandler)

  val finishFlowLayer: ZLayer[
    UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, FinishCommand, Unit]] = ZLayer.fromFunction:
    (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(FinishHandler(userRepo), eventHandler)

  /** Counterpart of [[finishFlowLayer]]: executed by the `JobRunner` when an access expires, not by
    * an endpoint.
    */
  val expireAccessFlowLayer: ZLayer[UserRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, ExpireAccessCommand, Unit]] = ZLayer
    .fromFunction: (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(ExpireAccessHandler(userRepo), eventHandler)

  /** The one flow built by hand rather than by `Flow(...)`: `MarkVideoWatchedHandler.withSession`
    * needs the session, so the handler can only be constructed per request, not at layer-build
    * time.
    */
  val markVideoWatchedFlowLayer: ZLayer[
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

  /** Built per request for the same reason as [[markVideoWatchedFlowLayer]]: the handler needs the
    * session.
    */
  val startPlaybackFlowLayer: ZLayer[
    VideoRepository[Task] & SessionContext & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, StartPlaybackCommand, Unit]] = ZLayer.fromFunction:
    (
        videoRepo: VideoRepository[Task],
        ctx: SessionContext,
        eventHandler: EventHandler[Task, Event]) =>
      perSession(ctx, eventHandler)(StartPlaybackHandler.withSession(videoRepo, _))

  val endPlaybackFlowLayer: ZLayer[
    SessionContext & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, EndPlaybackCommand, Unit]] = ZLayer.fromFunction:
    (ctx: SessionContext, eventHandler: EventHandler[Task, Event]) =>
      perSession(ctx, eventHandler)(EndPlaybackHandler.withSession[Task](_))

  private def perSession[C](ctx: SessionContext, eventHandler: EventHandler[Task, Event])(
      build: SessionData => Handler.Aux[Task, C, Unit]): Flow.Aux[Task, C, Unit] =
    import zio.interop.catz.given
    new Flow[Task, C]:
      type Result = Unit
      def execute(command: C): Task[Unit] =
        for
          session  <- ctx.getOrFail
          opResult <- build(session).handle(command)
          result   <- ZIO.fromEither(
            whitelabel.captal.core.Op.run(opResult).left.map(Flow.HandlerError(_)))
          (events, _) = result
          _ <- eventHandler.handle(events)
        yield ()

end Flows
