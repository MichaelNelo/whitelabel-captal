package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.QuestionAnswer
import whitelabel.captal.infra.eventhandlers.{
  AnswerPersistenceHandler,
  SessionPhaseHandler,
  SessionSurveyHandler,
  SurveyProgressHandler,
  UserPersistenceHandler
}
import whitelabel.captal.infra.{
  QuillSqlite,
  SessionContext,
  SessionService,
  SurveyRepositoryQuill,
  TransactionalEventHandler,
  UserRepositoryQuill
}
import zio.*
import zio.http.Server
import zio.interop.catz.*

object Main extends ZIOAppDefault:
  private val endpoints = SurveyRoutes.routes

  private val routes = ZioHttpInterpreter().toHttp(endpoints)

  private val serverConfigLayer: ZLayer[Any, Throwable, Server.Config] = ZLayer.fromZIO:
    ZIO.attempt:
      val c = ConfigFactory.load()
      Server.Config.default.binding(c.getString("server.host"), c.getInt("server.port"))

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  private val dataSourceLayer = Quill.DataSource.fromPrefix("database")

  private val sessionServiceLayer = SessionService.layer

  private val surveyRepoLayer = SurveyRepositoryQuill.layer

  private val userRepoLayer = UserRepositoryQuill.layer

  private val eventHandlerLayer
      : ZLayer[QuillSqlite & SessionContext, Nothing, EventHandler[Task, Event]] =
    ZLayer.fromFunction: (quill: QuillSqlite, ctx: SessionContext) =>
      val dbHandler = AnswerPersistenceHandler(ctx)
        .andThen(UserPersistenceHandler(ctx))
        .andThen(SessionPhaseHandler(ctx))
        .andThen(SessionSurveyHandler(ctx))
        .andThen(SurveyProgressHandler(ctx))
      TransactionalEventHandler(dbHandler, quill)

  private val answerEmailFlowLayer: ZLayer[
    SurveyRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerEmailCommand, QuestionAnswer]] = ZLayer.fromFunction:
    (surveyRepo: SurveyRepository[Task], eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerEmailHandler(surveyRepo), eventHandler)

  private val answerProfilingFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerProfilingCommand, QuestionAnswer]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerProfilingHandler(surveyRepo, userRepo), eventHandler)

  private val answerLocationFlowLayer: ZLayer[
    SurveyRepository[Task] & UserRepository[Task] & EventHandler[Task, Event],
    Nothing,
    Flow.Aux[Task, AnswerLocationCommand, QuestionAnswer]] = ZLayer.fromFunction:
    (
        surveyRepo: SurveyRepository[Task],
        userRepo: UserRepository[Task],
        eventHandler: EventHandler[Task, Event]) =>
      Flow(AnswerLocationHandler(surveyRepo, userRepo), eventHandler)

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
      Flow(ProvideNextIdentificationSurveyHandler(surveyRepo, userRepo), eventHandler)

  private val appLayers
      : ZLayer[Any, Throwable, SurveyRoutes.FullEnv] = ZLayer.make[SurveyRoutes.FullEnv](
    SessionContext.make,
    dataSourceLayer,
    quillLayer,
    sessionServiceLayer,
    surveyRepoLayer,
    userRepoLayer,
    eventHandlerLayer,
    answerEmailFlowLayer,
    answerProfilingFlowLayer,
    answerLocationFlowLayer,
    nextSurveyFlowLayer
  )

  private def logRoutes: ZIO[Server.Config, Nothing, Unit] =
    for
      config <- ZIO.service[Server.Config]
      binding    = config.address
      routeInfos = endpoints.map: e =>
        val method = e.endpoint.method.map(_.method).getOrElse("*")
        val path = e.endpoint.showPathTemplate()
        s"  $method $path"
      _ <- ZIO.logInfo(s"Server starting on ${binding.getHostString}:${binding.getPort}")
      _ <- ZIO.logInfo(s"Mounted routes:\n${routeInfos.mkString("\n")}")
    yield ()

  override val run: ZIO[Any, Throwable, Nothing] = (logRoutes *> Server.serve(routes)).provide(
    serverConfigLayer,
    Server.live,
    appLayers)
end Main
