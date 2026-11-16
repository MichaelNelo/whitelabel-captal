package whitelabel.captal.core.survey

import java.time.Instant
import java.util.UUID

import cats.Id as Same
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{
  QuestionInfo,
  SurveyAuthoringRepository,
  SurveyInfo,
  VideoOwner
}
import whitelabel.captal.core.survey.authoring.*
import whitelabel.captal.core.survey.question.{
  HierarchyLevel,
  Id as QuestionId,
  LocalizedText,
  OptionId
}

object SurveyAuthoringTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Profiling = SurveyInfo(Id(UUID(0, 1)), SurveyKind.Profiling, active = true)
  private val VideoSurvey = SurveyInfo(Id(UUID(0, 2)), SurveyKind.Advertiser, active = true)
  private def question(n: Long, active: Boolean = true) = QuestionInfo(
    QuestionId(UUID(1, n)),
    VideoSurvey.id,
    SurveyKind.Advertiser,
    active,
    n.toInt)
  private val First = question(1)
  private val Second = question(2)
  private val Retired = question(3, active = false)

  private val stored =
    new SurveyAuthoringRepository[Same]:
      private val surveys = List(Profiling, VideoSurvey)
      private val questions = List(First, Second, Retired)
      def survey(id: Id) = surveys.find(_.id == id)
      def question(id: QuestionId) = questions.find(_.id == id)
      def questionsOf(survey: Id) = questions.filter(_.survey == survey)
      def answersTo(question: QuestionId) = 7
      def activeSurveyOf(kind: SurveyKind) = surveys.find(_.kind == kind).map(_.id)
      def videoOwner(id: String) = Option.when(id == "vid-1")(VideoOwner(Some("loc-1"), None))

  private val Es = LocalizedText("Pregunta", "es")

  private def spec(answer: AnswerSpec, hierarchy: Boolean = false) = QuestionSpec(
    QuestionId(UUID(2, 1)),
    List(Es),
    answer,
    Nil,
    Nil,
    required = true,
    points = 10,
    hierarchyLevel = Option.when(hierarchy)(HierarchyLevel.State))

  /** The events and the result, or the authoring errors refused with. */
  private def outcome[A](op: Op[AppEvent, AppError, A]): Either[List[Error], (List[AppEvent], A)] =
    Op.run(op)
      .left
      .map(
        _.toList
          .flatMap:
            case AppError.SurveyAuthoring(found) =>
              found.toList
            case _ =>
              Nil)

  val tests = Tests:
    test("CreateSurvey"):
      test("refuses a second active identification survey of a kind"):
        assert(
          outcome(
            CreateSurveyHandler[Same](stored).handle(
              CreateSurveyCommand(
                Id(UUID(0, 9)),
                SurveyOwner.Identification(SurveyKind.Profiling),
                None,
                Now))) ==
            Left(List(Error.IdentificationSurveyExists(SurveyKind.Profiling, Profiling.id))))

      test("takes a video's survey's location from the video, and refuses one with no video"):
        val creating = CreateSurveyHandler[Same](stored)
        val id = Id(UUID(0, 9))
        assert(
          outcome(
            creating.handle(CreateSurveyCommand(id, SurveyOwner.Video("vid-1"), None, Now))) ==
            Right(
              (
                List(
                  AppEvent.SurveyAuthoring(
                    Event.SurveyCreated(
                      id,
                      SurveyKind.Advertiser,
                      Some("vid-1"),
                      Some("loc-1"),
                      None,
                      None,
                      Now))),
                CreatedSurvey(id, SurveyKind.Advertiser))),
          outcome(creating.handle(CreateSurveyCommand(id, SurveyOwner.Video("nope"), None, Now))) ==
            Left(List(Error.VideoNotFound("nope")))
        )

    test("AddQuestion"):
      test("asks it last"):
        val added = spec(AnswerSpec.Input(Nil))
        assert(
          outcome(
            AddQuestionHandler[Same](stored).handle(
              AddQuestionCommand(VideoSurvey.id, identification = false, added, Now))) ==
            Right(
              (
                List(AppEvent.SurveyAuthoring(Event.QuestionAdded(VideoSurvey.id, added, 4, Now))),
                QuestionPlaced(added.id, 4, Nil))))

      test("refuses every reason a question cannot be asked, at once"):
        val bad = spec(AnswerSpec.Radio(Nil), hierarchy = true).copy(points = -1)
        assert(
          outcome(
            AddQuestionHandler[Same](stored).handle(
              AddQuestionCommand(VideoSurvey.id, identification = false, bad, Now))) ==
            Left(List(Error.NoOptions, Error.HierarchyOutsideLocation, Error.NegativePoints(-1))))

      test("refuses a survey on the other side"):
        assert(
          outcome(
            AddQuestionHandler[Same](stored).handle(
              AddQuestionCommand(
                Profiling.id,
                identification = false,
                spec(AnswerSpec.Input(Nil)),
                Now))) == Left(List(Error.OtherSide(SurveyKind.Profiling))))

      test("refuses an option with no text, and a text repeating a locale"):
        val options = List(
          OptionSpec(OptionId(UUID(3, 1)), Nil),
          OptionSpec(OptionId(UUID(3, 2)), List(Es, Es)))
        assert(
          QuestionSpec.problems(spec(AnswerSpec.Radio(options)), SurveyKind.Advertiser) ==
            List(
              Error.OptionWithoutText(1),
              Error.RepeatedLocales(TextField.OptionText(2), List("es"))))

    test("RetireQuestion and ReinstateQuestion"):
      test("answer the active questions left after them"):
        assert(
          outcome(
            RetireQuestionHandler[Same](stored).handle(
              RetireQuestionCommand(First.id, identification = false, Now))) ==
            Right(
              (
                List(AppEvent.SurveyAuthoring(Event.QuestionRetired(First.id, Now))),
                QuestionActivity(answers = 7, remainingActive = 1))),
          outcome(
            ReinstateQuestionHandler[Same](stored).handle(
              ReinstateQuestionCommand(Retired.id, identification = false, Now))) ==
            Right(
              (
                List(AppEvent.SurveyAuthoring(Event.QuestionReinstated(Retired.id, Now))),
                QuestionActivity(answers = 7, remainingActive = 3)))
        )

    test("ReorderQuestions"):
      test("refuses an order that is not every active question once"):
        val reordering = ReorderQuestionsHandler[Same](stored)
        assert(
          outcome(
            reordering.handle(
              ReorderQuestionsCommand(
                VideoSurvey.id,
                identification = false,
                List(First.id.asString, Retired.id.asString),
                Now))) ==
            Left(List(Error.NotEveryQuestion(List(Second.id.asString), List(Retired.id.asString)))),
          outcome(
            reordering.handle(
              ReorderQuestionsCommand(
                VideoSurvey.id,
                identification = false,
                List(Second.id.asString, First.id.asString),
                Now))) ==
            Right(
              (
                List(
                  AppEvent.SurveyAuthoring(
                    Event.QuestionsReordered(VideoSurvey.id, List(Second.id, First.id), Now))),
                ()))
        )
end SurveyAuthoringTests
