package whitelabel.captal.api

import whitelabel.captal.api.Phases.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow, NextStep}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository, VideoRepository}
import whitelabel.captal.platform.eventhandlers.{
  AnswerPersistenceHandler,
  EventLogHandler,
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
import whitelabel.captal.platform.session.{JobQueue, SessionContext, SessionService}
import whitelabel.captal.platform.UnifiClient
import whitelabel.captal.api.session.CurrentLocation
import zio.*
// Every `Flow(...)` below needs `cats.Monad[Task]`, which this supplies. It was a file-level
// import of `Main` and is easy to lose in a move: without it ten of these fail to find an
// instance, and the error names cats rather than the import.
import zio.interop.catz.*

/** One layer per command the portal can execute, plus the handler chain they all write through.
  *
  * Every entry has the same shape — a `Flow` pairing one command handler with the event handler —
  * so what varies is only which repositories a handler reads and which `Phases` value it emits.
  * Collected here because `appLayers` lists them all and nothing else references any of them
  * individually.
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
        ctx,
        sessionService,
        unifiClient,
        jobQueue)
      // unifiAccess corre POST-commit; cualquier fallo HTTP no impacta el chain transaccional.
      transactional.andThen(unifiAccess)

  val answerEmailFlowLayer: ZLayer[SurveyRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, AnswerEmailCommand, NextStep]] =
    ZLayer.fromFunction:
      (surveyRepo: SurveyRepository[Task], eventHandler: EventHandler[Task, Event]) =>
        Flow(AnswerEmailHandler(surveyRepo, nextAfterIdentification), eventHandler)

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

  val finishFlowLayer: ZLayer[UserRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, FinishCommand, Unit]] = ZLayer
    .fromFunction: (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(FinishHandler(userRepo), eventHandler)

  /** Counterpart of [[finishFlowLayer]]: executed by the `JobRunner` when an access expires, not by
    * an endpoint. It depends on `eventHandlerLayer` — which is why the scheduler is split into
    * `JobQueue` (producer, no dependencies) and `JobRunner` (consumer), keeping the graph acyclic.
    */
  val expireAccessFlowLayer: ZLayer[UserRepository[
    Task] & EventHandler[Task, Event], Nothing, Flow.Aux[Task, ExpireAccessCommand, Unit]] = ZLayer
    .fromFunction: (userRepo: UserRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(ExpireAccessHandler(userRepo), eventHandler)

  /** The one flow built by hand rather than by `Flow(...)`.
    *
    * `MarkVideoWatchedHandler.withSession` needs the session, so the handler cannot be constructed
    * until the request is in flight — which is what `Flow`'s constructor assumes it can do once, at
    * layer-building time.
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

end Flows
