package whitelabel.captal.core.video

import java.time.Instant
import java.util.UUID

import cats.Id as Same
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.advertiser.Advertiser
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{
  AdvertiserRepository,
  CurrentFile,
  LocationRepository,
  VideoAuthoringRepository,
  VideoInfo,
  WaitingFile
}
import whitelabel.captal.core.location.{Location, LocationId, Slug, State}
import whitelabel.captal.core.survey.{AdvertiserId, Id as SurveyId}
import whitelabel.captal.core.survey.authoring.{
  AnswerSpec,
  Error as SurveyError,
  Event as SurveyEvent,
  QuestionSpec,
  SurveyKind
}
import whitelabel.captal.core.survey.question.{Id as QuestionId, LocalizedText}
import whitelabel.captal.core.video.authoring.*

object VideoAuthoringTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Here = Slug.unsafe("plaza")
  private val Plaza = Location(LocationId("plaza-id"), Here, "Plaza", true, State.Created)
  private val Acme = Advertiser(AdvertiserId(UUID(0, 1)), "Acme", 10, active = true)
  private val Gone = Advertiser(AdvertiserId(UUID(0, 2)), "Gone", 10, active = false)

  private val locations =
    new LocationRepository[Same]:
      def find(slug: Slug) = Option.when(slug == Here)(Plaza)
      def byId(id: LocationId) = Option.when(id == Plaza.id)(Plaza)
      def findDeployed(id: LocationId) = None

  private val advertisers =
    new AdvertiserRepository[Same]:
      def find(id: AdvertiserId) = List(Acme, Gone).find(_.id == id)
      def named(name: String) = Nil

  private def info(n: Long, status: VideoStatus) = VideoInfo(
    Id(UUID(1, n)),
    VideoType.Ad,
    status,
    Some(Plaza.id),
    Some(Here),
    minWatchSeconds = 0,
    current = None,
    waiting = None)

  /** Waiting for its first file. */
  private val Pending = info(1, VideoStatus.Pending).copy(
    minWatchSeconds = 45,
    waiting = Some(WaitingFile(FileId("first"), "plaza/videos/first")))

  /** Playing a 30-second file. */
  private val Playing = info(2, VideoStatus.Active).copy(current = Some(
    CurrentFile(FileId("played"), Some(30))))

  private val Other = info(3, VideoStatus.Active)

  private val videos =
    new VideoAuthoringRepository[Same]:
      private val stored = List(Pending, Playing, Other)
      def video(id: Id) = stored.find(_.id == id)
      def activeAt(location: Option[LocationId]) = stored
        .filter(v => v.location == location && v.status == VideoStatus.Active)
        .map(_.id)

  private val Es = LocalizedText("Bienvenida", "es")

  private val Question = QuestionSpec(
    QuestionId(UUID(2, 1)),
    List(LocalizedText("¿Te gustó?", "es")),
    AnswerSpec.Input(Nil),
    Nil,
    Nil,
    required = true,
    points = 10,
    hierarchyLevel = None)

  private def register(kind: VideoKind, questions: List[QuestionSpec]) = RegisterVideoCommand(
    Id(UUID(3, 1)),
    Here,
    kind,
    List(Es),
    Nil,
    minWatchSeconds = None,
    priority = 10,
    survey = SurveyId(UUID(3, 2)),
    questions = questions,
    occurredAt = Now)

  /** The events and the result, or the video errors refused with. */
  private def outcome[A](op: Op[AppEvent, AppError, A]): Either[List[Error], (List[AppEvent], A)] =
    Op.run(op)
      .left
      .map(
        _.toList
          .flatMap:
            case AppError.VideoAuthoring(found) =>
              found.toList
            case _ =>
              Nil)

  val tests = Tests:
    test("RegisterVideo"):
      test("registers an advertiser's video with its survey, named after both"):
        val command = register(VideoKind.Advertiser(Acme.id, None), List(Question))
        val key = "plaza/videos/" + command.id.asString
        assert(
          outcome(RegisterVideoHandler[Same](locations, advertisers).handle(command)) ==
            Right(
              (
                List(
                  AppEvent.VideoAuthoring(
                    Event.VideoRegistered(
                      command.id,
                      Plaza.id,
                      command.kind,
                      List(Es),
                      Nil,
                      0,
                      10,
                      FileId.first(command.id),
                      key,
                      Now)),
                  AppEvent.SurveyAuthoring(
                    SurveyEvent.SurveyCreated(
                      command.survey,
                      SurveyKind.Advertiser,
                      Some(command.id.asString),
                      Some("plaza-id"),
                      Some(Acme.id.asString),
                      Some("Acme — Bienvenida"),
                      Now)),
                  AppEvent.SurveyAuthoring(
                    SurveyEvent.QuestionAdded(command.survey, Question, 1, Now))
                ),
                RegisteredVideo(command.id, key, Some(command.survey), List(Question.id)))))

      test("registers a promo with no questions without a survey"):
        val registered = outcome(
          RegisterVideoHandler[Same](locations, advertisers).handle(
            register(VideoKind.Promo(None), Nil)))
        assert(registered.map(_._1.size) == Right(1), registered.map(_._2.survey) == Right(None))

      test("refuses every reason at once"):
        val command = register(
          VideoKind.Advertiser(Gone.id, None),
          List(Question.copy(points = -1))).copy(title = Nil, minWatchSeconds = Some(0))
        assert(
          outcome(RegisterVideoHandler[Same](locations, advertisers).handle(command)) ==
            Left(
              List(
                Error.AdvertiserRetired("Gone"),
                Error.NoTitle,
                Error.MinWatchNotPositive(0),
                Error.QuestionRefused(1, SurveyError.NegativePoints(-1)))))

      test("refuses an advertiser's video with nothing to ask"):
        assert(
          outcome(
            RegisterVideoHandler[Same](locations, advertisers).handle(
              register(VideoKind.Advertiser(Acme.id, None), Nil))) == Left(List(Error.NoQuestions)))

    test("ConfirmVideoFile"):
      test("puts a pending video into rotation, its minimum watch lowered to the file"):
        assert(
          outcome(
            ConfirmVideoFileHandler[Same](videos).handle(
              ConfirmVideoFileCommand(Pending.id, FileId("first"), 30, Now))) ==
            Right(
              (
                List(
                  AppEvent.VideoAuthoring(
                    Event.FileConfirmed(
                      Pending.id,
                      FileId("first"),
                      30,
                      30,
                      VideoStatus.Active,
                      Now))),
                FileConfirmation(
                  key = "plaza/videos/first",
                  active = true,
                  durationSeconds = 30,
                  minWatchSeconds = 30,
                  minWatchAdjustedFrom = Some(45),
                  replaced = false))))

      test("refuses a file the video no longer waits for, and one it never did"):
        val confirming = ConfirmVideoFileHandler[Same](videos)
        assert(
          outcome(
            confirming.handle(ConfirmVideoFileCommand(Pending.id, FileId("older"), 30, Now))) ==
            Left(List(Error.FileNoLongerWaiting(Pending.id, FileId("older")))),
          outcome(
            confirming.handle(ConfirmVideoFileCommand(Playing.id, FileId("played"), 0, Now))) ==
            Left(List(Error.DurationNotPositive(0), Error.NoFileWaiting(Playing.id)))
        )

    test("StartFileReplacement"):
      test("reserves a new file, and refuses a video waiting for its first"):
        val starting = StartFileReplacementHandler[Same](videos)
        assert(
          outcome(starting.handle(StartFileReplacementCommand(Playing.id, FileId("new"), Now))) ==
            Right(
              (
                List(
                  AppEvent.VideoAuthoring(
                    Event.FileReserved(Playing.id, FileId("new"), "plaza/videos/new", Now))),
                AwaitedFile(FileId("new"), "plaza/videos/new"))),
          outcome(starting.handle(StartFileReplacementCommand(Pending.id, FileId("new"), Now))) ==
            Left(List(Error.StillPending(Pending.id)))
        )

    test("UpdateVideo"):
      test("lowers the minimum watch to the file it plays"):
        assert(
          outcome(
            UpdateVideoHandler[Same](videos).handle(
              UpdateVideoCommand(Playing.id, None, None, Some(60), None, None, Some(" "), Now))) ==
            Right(
              (
                List(
                  AppEvent.VideoAuthoring(
                    Event.VideoUpdated(
                      Playing.id,
                      None,
                      None,
                      Some(30),
                      None,
                      None,
                      Some(None),
                      Now))),
                VideoCorrection(Some(60)))))

    test("RetireVideo and ReinstateVideo"):
      test("answer the videos left in rotation at the location"):
        assert(
          outcome(RetireVideoHandler[Same](videos).handle(RetireVideoCommand(Playing.id, Now))) ==
            Right(
              (
                List(AppEvent.VideoAuthoring(Event.VideoRetired(Playing.id, Now))),
                VideosInRotation(1))),
          outcome(
            ReinstateVideoHandler[Same](videos).handle(ReinstateVideoCommand(Pending.id, Now))) ==
            Left(List(Error.StillPending(Pending.id)))
        )
end VideoAuthoringTests
