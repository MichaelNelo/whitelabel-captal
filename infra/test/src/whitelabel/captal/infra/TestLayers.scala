package whitelabel.captal.infra

import io.getquill.jdbczio.Quill
import whitelabel.captal.api.SurveyRoutes
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Event, EventHandler, Flow}
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.QuestionAnswer
import whitelabel.captal.infra.eventhandlers.*
import zio.*
import zio.interop.catz.*

object TestLayers:
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

  val quill: ZLayer[Any, Throwable, QuillSqlite] = dataSourceLayer >>> quillLayer

  val testEnv: ZLayer[Any, Throwable, SurveyRoutes.FullEnv & QuillSqlite] = ZLayer.make[
    SurveyRoutes.FullEnv & QuillSqlite](
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
end TestLayers
