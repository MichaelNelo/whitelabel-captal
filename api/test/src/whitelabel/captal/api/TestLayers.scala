package whitelabel.captal.api

import javax.sql.DataSource

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import org.sqlite.SQLiteDataSource
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.infra.eventhandlers.*
import whitelabel.captal.infra.repositories.{SurveyRepositoryQuill, UserRepositoryQuill}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.services.LocaleService
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*
import zio.interop.catz.*

object TestLayers:
  // Phase after identification question for tests
  private val nextPhaseAfterIdentificationQuestion: Phase = Phase.AdvertiserVideo
  private val nextStepAfterIdentificationQuestion: NextStep = NextStep(
    nextPhaseAfterIdentificationQuestion)
  private val testConfig = ConfigFactory.load("test.conf")

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  private val dataSourceLayer: ZLayer[Any, Throwable, DataSource] = ZLayer.fromZIO:
    ZIO.attempt:
      val ds = new SQLiteDataSource()
      ds.setUrl(testConfig.getString("database.dataSource.url"))
      ds

  private val sessionServiceLayer = SessionService.layer

  private val localeServiceLayer = LocaleService.layer

  private val surveyRepoLayer = SurveyRepositoryQuill.layer

  private val userRepoLayer = UserRepositoryQuill.layer

  private val eventHandlerLayer
      : ZLayer[QuillSqlite & SessionContext, Nothing, EventHandler[Task, Event]] = ZLayer
    .fromFunction: (quill: QuillSqlite, ctx: SessionContext) =>
      val dbHandler = AnswerPersistenceHandler(ctx)
        .andThen(UserPersistenceHandler(ctx))
        .andThen(SessionPhaseHandler(ctx, nextPhaseAfterIdentificationQuestion))
        .andThen(SessionSurveyHandler(ctx))
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

  val quill: ZLayer[Any, Throwable, QuillSqlite] = dataSourceLayer >>> quillLayer

  type TestEnv =
    SessionContext & SessionService & LocaleService & SurveyRoutes.AnswerEmailFlowType &
      SurveyRoutes.AnswerProfilingFlowType & SurveyRoutes.AnswerLocationFlowType &
      SurveyRoutes.NextSurveyFlowType & QuillSqlite

  val testEnv: ZLayer[Any, Throwable, TestEnv] = ZLayer.make[TestEnv](
    SessionContext.make,
    dataSourceLayer,
    quillLayer,
    sessionServiceLayer,
    localeServiceLayer,
    surveyRepoLayer,
    userRepoLayer,
    eventHandlerLayer,
    answerEmailFlowLayer,
    answerProfilingFlowLayer,
    answerLocationFlowLayer,
    nextSurveyFlowLayer
  )
end TestLayers
