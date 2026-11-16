package whitelabel.captal.mcp.tools

import whitelabel.captal.core.advertiser.Advertiser
import whitelabel.captal.core.application.commands.{
  AddQuestionCommand,
  AddQuestionHandler,
  AnswersKept,
  ChangeRecoveryPolicyCommand,
  ChangeRecoveryPolicyHandler,
  CreateAdvertiserCommand,
  CreateAdvertiserHandler,
  CreateSurveyCommand,
  CreateSurveyHandler,
  Handler,
  QuestionActivity,
  QuestionPlaced,
  ReinstateQuestionCommand,
  ReinstateQuestionHandler,
  ReorderQuestionsCommand,
  ReorderQuestionsHandler,
  RetireQuestionCommand,
  RetireQuestionHandler,
  UpdateQuestionCommand,
  UpdateQuestionHandler,
  PublishTermsVersionCommand,
  PublishTermsVersionHandler,
  ReinstateAdvertiserCommand,
  ReinstateAdvertiserHandler,
  RemoveTermsTranslationCommand,
  RemoveTermsTranslationHandler,
  RemoveTranslationCommand,
  RemoveTranslationHandler,
  RetireAdvertiserCommand,
  RetireAdvertiserHandler,
  SetTermsTranslationCommand,
  SetTermsTranslationHandler,
  SetTranslationsCommand,
  SetTranslationsHandler,
  TranslationRemoval,
  UpdateAdvertiserCommand,
  UpdateAdvertiserHandler
}
import whitelabel.captal.core.application.{Event, Flow}
import whitelabel.captal.core.infrastructure.{AdvertiserRepository, SurveyAuthoringRepository}
import whitelabel.captal.core.recovery.PolicyOverrides
import whitelabel.captal.core.survey.authoring.SurveyKind
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.eventhandlers.{
  AdvertiserPersistenceHandler,
  DbEventHandler,
  EventLogHandler,
  RecoveryHandler,
  SurveyAuthoringPersistenceHandler,
  TermsPersistenceHandler,
  TransactionalEventHandler,
  TranslationPersistenceHandler
}
import whitelabel.captal.platform.repositories.{
  AdvertiserRepositoryQuill,
  LocationRepositoryQuill,
  RecoveryPolicyRepositoryQuill,
  SurveyAuthoringRepositoryQuill,
  TermsRepositoryQuill,
  TranslationRepositoryQuill
}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** The content's commands as flows, composed once, as [[LocationFlows]] composes the location's:
  * each through the chain that writes what it decided and logs it under the operator a call
  * publishes ([[OperatorContext]]).
  */
object ContentFlows:
  type ChangeRecoveryPolicy = Flow.Aux[Task, ChangeRecoveryPolicyCommand, PolicyOverrides]
  type PublishTermsVersion = Flow.Aux[Task, PublishTermsVersionCommand, Int]
  type SetTermsTranslation = Flow.Aux[Task, SetTermsTranslationCommand, Unit]
  type RemoveTermsTranslation = Flow.Aux[Task, RemoveTermsTranslationCommand, Unit]
  type SetTranslations = Flow.Aux[Task, SetTranslationsCommand, List[String]]
  type RemoveTranslation = Flow.Aux[Task, RemoveTranslationCommand, TranslationRemoval]
  type CreateAdvertiser = Flow.Aux[Task, CreateAdvertiserCommand, Advertiser]
  type UpdateAdvertiser = Flow.Aux[Task, UpdateAdvertiserCommand, Advertiser]
  type RetireAdvertiser = Flow.Aux[Task, RetireAdvertiserCommand, Unit]
  type ReinstateAdvertiser = Flow.Aux[Task, ReinstateAdvertiserCommand, Unit]
  type CreateSurvey = Flow.Aux[Task, CreateSurveyCommand, SurveyKind]
  type AddQuestion = Flow.Aux[Task, AddQuestionCommand, QuestionPlaced]
  type UpdateQuestion = Flow.Aux[Task, UpdateQuestionCommand, AnswersKept]
  type RetireQuestion = Flow.Aux[Task, RetireQuestionCommand, QuestionActivity]
  type ReinstateQuestion = Flow.Aux[Task, ReinstateQuestionCommand, QuestionActivity]
  type ReorderQuestions = Flow.Aux[Task, ReorderQuestionsCommand, Unit]
  type Surveys =
    CreateSurvey & AddQuestion & UpdateQuestion & RetireQuestion & ReinstateQuestion &
      ReorderQuestions
  type All =
    ChangeRecoveryPolicy & PublishTermsVersion & SetTermsTranslation & RemoveTermsTranslation &
      SetTranslations & RemoveTranslation & CreateAdvertiser & UpdateAdvertiser & RetireAdvertiser &
      ReinstateAdvertiser & Surveys

  /** What `writes` persists, then the event log, in one transaction. */
  private def logged(quill: QuillSqlite, operators: OperatorContext)(
      writes: DbEventHandler[Event]) = TransactionalEventHandler(
    writes.andThen(EventLogHandler.forOperator(operators)),
    quill)

  def changeRecoveryPolicy(quill: QuillSqlite, operators: OperatorContext): ChangeRecoveryPolicy =
    Flow(
      ChangeRecoveryPolicyHandler[Task](
        LocationRepositoryQuill(quill),
        RecoveryPolicyRepositoryQuill(quill)),
      logged(quill, operators)(RecoveryHandler()))

  def publishTermsVersion(quill: QuillSqlite, operators: OperatorContext): PublishTermsVersion =
    Flow(
      PublishTermsVersionHandler[Task](
        TermsRepositoryQuill(quill),
        TermsRepositoryQuill.texts(quill)),
      logged(quill, operators)(TermsPersistenceHandler()))

  def setTermsTranslation(quill: QuillSqlite, operators: OperatorContext): SetTermsTranslation =
    Flow(
      SetTermsTranslationHandler[Task](TermsRepositoryQuill(quill)),
      logged(quill, operators)(TermsPersistenceHandler()))

  def removeTermsTranslation(
      quill: QuillSqlite,
      operators: OperatorContext): RemoveTermsTranslation = Flow(
    RemoveTermsTranslationHandler[Task](
      TermsRepositoryQuill(quill),
      TermsRepositoryQuill.texts(quill)),
    logged(quill, operators)(TermsPersistenceHandler())
  )

  def setTranslations(quill: QuillSqlite, operators: OperatorContext): SetTranslations = Flow(
    SetTranslationsHandler[Task](LocationRepositoryQuill(quill), TranslationRepositoryQuill(quill)),
    logged(quill, operators)(TranslationPersistenceHandler())
  )

  def removeTranslation(quill: QuillSqlite, operators: OperatorContext): RemoveTranslation = Flow(
    RemoveTranslationHandler[Task](
      LocationRepositoryQuill(quill),
      TranslationRepositoryQuill(quill)),
    logged(quill, operators)(TranslationPersistenceHandler())
  )

  /** An advertiser's command, through the chain that writes advertisers. */
  private def advertiserFlow[C, R](quill: QuillSqlite, operators: OperatorContext)(
      handler: AdvertiserRepository[Task] => Handler.Aux[Task, C, R]): Flow.Aux[Task, C, R] = Flow(
    handler(AdvertiserRepositoryQuill(quill)),
    logged(quill, operators)(AdvertiserPersistenceHandler()))

  def createAdvertiser(quill: QuillSqlite, operators: OperatorContext): CreateAdvertiser =
    advertiserFlow(quill, operators)(CreateAdvertiserHandler[Task](_))

  def updateAdvertiser(quill: QuillSqlite, operators: OperatorContext): UpdateAdvertiser =
    advertiserFlow(quill, operators)(UpdateAdvertiserHandler[Task](_))

  def retireAdvertiser(quill: QuillSqlite, operators: OperatorContext): RetireAdvertiser =
    advertiserFlow(quill, operators)(RetireAdvertiserHandler[Task](_))

  def reinstateAdvertiser(quill: QuillSqlite, operators: OperatorContext): ReinstateAdvertiser =
    advertiserFlow(quill, operators)(ReinstateAdvertiserHandler[Task](_))

  /** A survey authoring command, through the chain that writes surveys and their questions. */
  private def surveyFlow[C, R](quill: QuillSqlite, operators: OperatorContext)(
      handler: SurveyAuthoringRepository[Task] => Handler.Aux[Task, C, R]): Flow.Aux[Task, C, R] =
    Flow(
      handler(SurveyAuthoringRepositoryQuill(quill)),
      logged(quill, operators)(SurveyAuthoringPersistenceHandler()))

  def createSurvey(quill: QuillSqlite, operators: OperatorContext): CreateSurvey =
    surveyFlow(quill, operators)(CreateSurveyHandler[Task](_))

  def addQuestion(quill: QuillSqlite, operators: OperatorContext): AddQuestion =
    surveyFlow(quill, operators)(AddQuestionHandler[Task](_))

  def updateQuestion(quill: QuillSqlite, operators: OperatorContext): UpdateQuestion =
    surveyFlow(quill, operators)(UpdateQuestionHandler[Task](_))

  def retireQuestion(quill: QuillSqlite, operators: OperatorContext): RetireQuestion =
    surveyFlow(quill, operators)(RetireQuestionHandler[Task](_))

  def reinstateQuestion(quill: QuillSqlite, operators: OperatorContext): ReinstateQuestion =
    surveyFlow(quill, operators)(ReinstateQuestionHandler[Task](_))

  def reorderQuestions(quill: QuillSqlite, operators: OperatorContext): ReorderQuestions =
    surveyFlow(quill, operators)(ReorderQuestionsHandler[Task](_))

  private type Needs = QuillSqlite & OperatorContext

  val createSurveyLayer: ZLayer[Needs, Nothing, CreateSurvey] = ZLayer.fromFunction(createSurvey)

  val addQuestionLayer: ZLayer[Needs, Nothing, AddQuestion] = ZLayer.fromFunction(addQuestion)

  val updateQuestionLayer: ZLayer[Needs, Nothing, UpdateQuestion] = ZLayer.fromFunction(
    updateQuestion)

  val retireQuestionLayer: ZLayer[Needs, Nothing, RetireQuestion] = ZLayer.fromFunction(
    retireQuestion)

  val reinstateQuestionLayer: ZLayer[Needs, Nothing, ReinstateQuestion] = ZLayer.fromFunction(
    reinstateQuestion)

  val reorderQuestionsLayer: ZLayer[Needs, Nothing, ReorderQuestions] = ZLayer.fromFunction(
    reorderQuestions)

  val createAdvertiserLayer: ZLayer[Needs, Nothing, CreateAdvertiser] = ZLayer.fromFunction(
    createAdvertiser)

  val updateAdvertiserLayer: ZLayer[Needs, Nothing, UpdateAdvertiser] = ZLayer.fromFunction(
    updateAdvertiser)

  val retireAdvertiserLayer: ZLayer[Needs, Nothing, RetireAdvertiser] = ZLayer.fromFunction(
    retireAdvertiser)

  val reinstateAdvertiserLayer: ZLayer[Needs, Nothing, ReinstateAdvertiser] = ZLayer.fromFunction(
    reinstateAdvertiser)

  val setTranslationsLayer: ZLayer[Needs, Nothing, SetTranslations] = ZLayer.fromFunction(
    setTranslations)

  val removeTranslationLayer: ZLayer[Needs, Nothing, RemoveTranslation] = ZLayer.fromFunction(
    removeTranslation)

  val changeRecoveryPolicyLayer: ZLayer[Needs, Nothing, ChangeRecoveryPolicy] = ZLayer.fromFunction(
    changeRecoveryPolicy)

  val publishTermsVersionLayer: ZLayer[Needs, Nothing, PublishTermsVersion] = ZLayer.fromFunction(
    publishTermsVersion)

  val setTermsTranslationLayer: ZLayer[Needs, Nothing, SetTermsTranslation] = ZLayer.fromFunction(
    setTermsTranslation)

  val removeTermsTranslationLayer: ZLayer[Needs, Nothing, RemoveTermsTranslation] = ZLayer
    .fromFunction(removeTermsTranslation)

  /** Every one, for a caller that builds its environment by hand — a test that runs the tools
    * without `Main`'s layers. Added one by one, so each is found by its own type.
    */
  def environment(quill: QuillSqlite, operators: OperatorContext): ZEnvironment[All] = ZEnvironment
    .empty
    .add[ChangeRecoveryPolicy](changeRecoveryPolicy(quill, operators))
    .add[PublishTermsVersion](publishTermsVersion(quill, operators))
    .add[SetTermsTranslation](setTermsTranslation(quill, operators))
    .add[RemoveTermsTranslation](removeTermsTranslation(quill, operators))
    .add[SetTranslations](setTranslations(quill, operators))
    .add[RemoveTranslation](removeTranslation(quill, operators))
    .add[CreateAdvertiser](createAdvertiser(quill, operators))
    .add[UpdateAdvertiser](updateAdvertiser(quill, operators))
    .add[RetireAdvertiser](retireAdvertiser(quill, operators))
    .add[ReinstateAdvertiser](reinstateAdvertiser(quill, operators))
    .add[CreateSurvey](createSurvey(quill, operators))
    .add[AddQuestion](addQuestion(quill, operators))
    .add[UpdateQuestion](updateQuestion(quill, operators))
    .add[RetireQuestion](retireQuestion(quill, operators))
    .add[ReinstateQuestion](reinstateQuestion(quill, operators))
    .add[ReorderQuestions](reorderQuestions(quill, operators))
end ContentFlows
