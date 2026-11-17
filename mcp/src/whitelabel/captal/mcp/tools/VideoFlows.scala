package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.commands.{
  AwaitedFile,
  CollectReplacedFilesCommand,
  CollectReplacedFilesHandler,
  ConfirmVideoFileCommand,
  ConfirmVideoFileHandler,
  ExpirePlaybackCommand,
  ExpirePlaybackHandler,
  FileConfirmation,
  Handler,
  RegisterVideoCommand,
  RegisterVideoHandler,
  RegisteredVideo,
  ReinstateVideoCommand,
  ReinstateVideoHandler,
  RetireVideoCommand,
  RetireVideoHandler,
  StartFileReplacementCommand,
  StartFileReplacementHandler,
  UpdateVideoCommand,
  UpdateVideoHandler,
  VideoCorrection,
  VideosInRotation
}
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.infrastructure.VideoAuthoringRepository
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.eventhandlers.{
  EventLogHandler,
  PlaybackExpiryHandler,
  ReplacedFileDeletionHandler,
  SurveyAuthoringPersistenceHandler,
  TransactionalEventHandler,
  VideoPersistenceHandler
}
import whitelabel.captal.platform.repositories.{
  AdvertiserRepositoryQuill,
  LocationRepositoryQuill,
  PlaybackRepositoryQuill,
  VideoAuthoringRepositoryQuill
}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** The videos' commands as flows, composed once: each through the chain that writes the videos and
  * their files — the URL a file is fetched from is the store's — then a registration's survey and
  * questions, then the log under the operator a call publishes, in one transaction.
  */
object VideoFlows:
  type RegisterVideo = Flow.Aux[Task, RegisterVideoCommand, RegisteredVideo]
  type ConfirmVideoFile = Flow.Aux[Task, ConfirmVideoFileCommand, FileConfirmation]
  type StartFileReplacement = Flow.Aux[Task, StartFileReplacementCommand, AwaitedFile]
  type UpdateVideo = Flow.Aux[Task, UpdateVideoCommand, VideoCorrection]
  type RetireVideo = Flow.Aux[Task, RetireVideoCommand, VideosInRotation]
  type ReinstateVideo = Flow.Aux[Task, ReinstateVideoCommand, VideosInRotation]
  type CollectReplacedFiles = Flow.Aux[Task, CollectReplacedFilesCommand, Int]
  type ExpirePlayback = Flow.Aux[Task, ExpirePlaybackCommand, Boolean]

  /** The background jobs': the files nobody plays any more, and the playbacks their pages never
    * ended.
    */
  type Upkeep = CollectReplacedFiles & ExpirePlayback
  type All =
    RegisterVideo & ConfirmVideoFile & StartFileReplacement & UpdateVideo & RetireVideo &
      ReinstateVideo & Upkeep

  private def written(quill: QuillSqlite, operators: OperatorContext, store: AssetStore) =
    TransactionalEventHandler(
      VideoPersistenceHandler(store)
        .andThen(SurveyAuthoringPersistenceHandler())
        .andThen(EventLogHandler.forOperator(operators)),
      quill)

  def registerVideo(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): RegisterVideo = Flow(
    RegisterVideoHandler[Task](LocationRepositoryQuill(quill), AdvertiserRepositoryQuill(quill)),
    written(quill, operators, store))

  /** A command on an existing video, through the same chain. */
  private def videoFlow[C, R](quill: QuillSqlite, operators: OperatorContext, store: AssetStore)(
      handler: VideoAuthoringRepository[Task] => Handler.Aux[Task, C, R]): Flow.Aux[Task, C, R] =
    Flow(handler(VideoAuthoringRepositoryQuill(quill, store)), written(quill, operators, store))

  def confirmVideoFile(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): ConfirmVideoFile =
    videoFlow(quill, operators, store)(ConfirmVideoFileHandler[Task](_))

  def startFileReplacement(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): StartFileReplacement =
    videoFlow(quill, operators, store)(StartFileReplacementHandler[Task](_))

  def updateVideo(quill: QuillSqlite, operators: OperatorContext, store: AssetStore): UpdateVideo =
    videoFlow(quill, operators, store)(UpdateVideoHandler[Task](_))

  def retireVideo(quill: QuillSqlite, operators: OperatorContext, store: AssetStore): RetireVideo =
    videoFlow(quill, operators, store)(RetireVideoHandler[Task](_))

  def reinstateVideo(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): ReinstateVideo =
    videoFlow(quill, operators, store)(ReinstateVideoHandler[Task](_))

  /** A pass deleting the files nobody plays: marked deleted in the transaction, their objects gone
    * after the commit.
    */
  def collectReplacedFiles(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): CollectReplacedFiles = Flow(
    CollectReplacedFilesHandler[Task](VideoAuthoringRepositoryQuill(quill, store)),
    TransactionalEventHandler(
      VideoPersistenceHandler(store).andThen(EventLogHandler.forOperator(operators)),
      quill).andThen(ReplacedFileDeletionHandler(quill, store))
  )

  def expirePlayback(quill: QuillSqlite, operators: OperatorContext): ExpirePlayback = Flow(
    ExpirePlaybackHandler[Task](PlaybackRepositoryQuill(quill)),
    TransactionalEventHandler(
      PlaybackExpiryHandler().andThen(EventLogHandler.forOperator(operators)),
      quill)
  )

  private type Needs = QuillSqlite & OperatorContext & AssetStore

  val registerVideoLayer: ZLayer[Needs, Nothing, RegisterVideo] = ZLayer.fromFunction(registerVideo)

  val confirmVideoFileLayer: ZLayer[Needs, Nothing, ConfirmVideoFile] = ZLayer.fromFunction(
    confirmVideoFile)

  val startFileReplacementLayer: ZLayer[Needs, Nothing, StartFileReplacement] = ZLayer.fromFunction(
    startFileReplacement)

  val updateVideoLayer: ZLayer[Needs, Nothing, UpdateVideo] = ZLayer.fromFunction(updateVideo)

  val retireVideoLayer: ZLayer[Needs, Nothing, RetireVideo] = ZLayer.fromFunction(retireVideo)

  val reinstateVideoLayer: ZLayer[Needs, Nothing, ReinstateVideo] = ZLayer.fromFunction(
    reinstateVideo)

  val collectReplacedFilesLayer: ZLayer[Needs, Nothing, CollectReplacedFiles] = ZLayer.fromFunction(
    collectReplacedFiles)

  val expirePlaybackLayer: ZLayer[QuillSqlite & OperatorContext, Nothing, ExpirePlayback] = ZLayer
    .fromFunction(expirePlayback)

  /** Every one, for a caller that builds its environment by hand. */
  def environment(
      quill: QuillSqlite,
      operators: OperatorContext,
      store: AssetStore): ZEnvironment[All] = ZEnvironment
    .empty
    .add[RegisterVideo](registerVideo(quill, operators, store))
    .add[ConfirmVideoFile](confirmVideoFile(quill, operators, store))
    .add[StartFileReplacement](startFileReplacement(quill, operators, store))
    .add[UpdateVideo](updateVideo(quill, operators, store))
    .add[RetireVideo](retireVideo(quill, operators, store))
    .add[ReinstateVideo](reinstateVideo(quill, operators, store))
    .add[CollectReplacedFiles](collectReplacedFiles(quill, operators, store))
    .add[ExpirePlayback](expirePlayback(quill, operators))
end VideoFlows
