package whitelabel.captal.core.application

import java.time.Instant

import cats.{Id, Parallel}
import utest.*
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.{AdvertiserId, State as SurveyState, Survey}
import whitelabel.captal.core.user.{State as UserState, User}
import whitelabel.captal.core.{Op, device, survey, user, video}

private val testNextStep = NextStep(Phase.Ready)

object HandlerTests extends TestSuite:

  // Commands take `occurredAt` as data, so a fixed Instant keeps the handlers deterministic.
  private val FixedNow: Instant = Instant.EPOCH

  private val TestDevice: user.DeviceId = user.DeviceId.generate

  private def makeRadioQuestion(optionId: OptionId): QuestionToAnswer = QuestionToAnswer(
    id = survey.question.Id.generate,
    text = LocalizedText("Test question", "en"),
    description = None,
    placeholder = None,
    questionType = QuestionType.Radio(
      List(QuestionOption(optionId, LocalizedText("Opt", "en"), 1, None))),
    commonRules = Nil,
    pointsAwarded = 10
  )

  private def makeInputQuestion(): QuestionToAnswer = QuestionToAnswer(
    id = survey.question.Id.generate,
    text = LocalizedText("Enter your email", "en"),
    description = None,
    placeholder = Some(LocalizedText("email@example.com", "en")),
    questionType = QuestionType.Input(List(TextRule.Email)),
    commonRules = Nil,
    pointsAwarded = 10
  )

  private def makeSurveyWithEmail(
      question: QuestionToAnswer): Survey[SurveyState.WithEmailQuestion] = Survey(
    survey.Id.generate,
    SurveyState.WithEmailQuestion(question))

  private def makeSurveyWithProfiling(
      question: QuestionToAnswer): Survey[SurveyState.WithProfilingQuestion] = Survey(
    survey.Id.generate,
    SurveyState.WithProfilingQuestion(question))

  private def makeSurveyWithLocation(
      question: QuestionToAnswer): Survey[SurveyState.WithLocationQuestion] = Survey(
    survey.Id.generate,
    SurveyState.WithLocationQuestion(question, HierarchyLevel.State))

  /** Defaults to the advertiser case; pass `None` for the promo case, a video survey with no
    * advertiser.
    */
  private def makeSurveyWithAdvertiser(
      question: QuestionToAnswer,
      advertiserId: Option[AdvertiserId] = Some(AdvertiserId.generate))
      : Survey[SurveyState.WithAdvertiserQuestion] = Survey(
    survey.Id.generate,
    SurveyState.WithAdvertiserQuestion(advertiserId, question))

  private def makeUserAnswering(
      surveyId: survey.Id,
      questionId: survey.question.Id): User[UserState.AnsweringQuestion] = User(
    user.Id.generate,
    UserState.AnsweringQuestion(surveyId, questionId))

  private def mockSurveyRepo(
      emailSurvey: Option[Survey[SurveyState.WithEmailQuestion]] = None,
      profilingSurvey: Option[Survey[SurveyState.WithProfilingQuestion]] = None,
      locationSurvey: Option[Survey[SurveyState.WithLocationQuestion]] = None,
      advertiserSurvey: Option[Survey[SurveyState.WithAdvertiserQuestion]] = None)
      : SurveyRepository[Id] =
    new SurveyRepository[Id]:
      def findAssignedEmailSurvey() = emailSurvey
      def findWithProfilingQuestion(id: survey.Id, qId: survey.question.Id) = profilingSurvey
        .filter(s => s.id == id && s.state.question.id == qId)
      def findWithLocationQuestion(id: survey.Id, qId: survey.question.Id) = locationSurvey.filter(
        s => s.id == id && s.state.question.id == qId)
      def findWithAdvertiserQuestion(id: survey.Id, qId: survey.question.Id) = advertiserSurvey
        .filter(s => s.id == id && s.state.question.id == qId)
      def findNextIdentificationSurvey() = None
      def findNextAdvertiserSurvey(videoId: video.Id) = None

  private def mockUserRepo(
      answeringUser: Option[User[UserState.AnsweringQuestion]] = None,
      withEmailUser: Option[User[UserState.WithEmail]] = None,
      knownEmails: Map[String, user.Id] = Map.empty): UserRepository[Id] =
    new UserRepository[Id]:
      def findWithEmail() = withEmailUser
      def findIdByEmail(email: user.Email) = knownEmails.get(email.value)
      def findAnswering() = answeringUser
      def findWatchingVideo() = None
      def findAnsweringVideoSurvey() = None
      def findReadyUser() = None

  given Parallel[Id] = Parallel.identity[Id]

  val tests = Tests:

    test("AnswerEmailHandler"):
      test("returns NoSurveyAssigned when no email survey assigned"):
        val surveyRepo = mockSurveyRepo()
        val handler = AnswerEmailHandler(surveyRepo, mockUserRepo(), testNextStep)
        val cmd = AnswerEmailCommand(
          deviceId = TestDevice,
          answer = AnswerValue.Text("user@example.com"),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.NoSurveyAssigned =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("returns InvalidEmailFormat when answer is not Text"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val emailSurvey = makeSurveyWithEmail(question)
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(surveyRepo, mockUserRepo(), testNextStep)
        val cmd = AnswerEmailCommand(
          deviceId = TestDevice,
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.InvalidEmailFormat(_) =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("returns InvalidEmailFormat when email format is invalid"):
        val question = makeInputQuestion()
        val emailSurvey = makeSurveyWithEmail(question)
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(surveyRepo, mockUserRepo(), testNextStep)
        val cmd = AnswerEmailCommand(
          deviceId = TestDevice,
          answer = AnswerValue.Text("not-an-email"),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.InvalidEmailFormat(_) =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("succeeds with valid email"):
        val question = makeInputQuestion()
        val emailSurvey = makeSurveyWithEmail(question)
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(surveyRepo, mockUserRepo(), testNextStep)
        val cmd = AnswerEmailCommand(
          deviceId = TestDevice,
          answer = AnswerValue.Text("user@example.com"),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, nextStep) = result.toOption.get
        assert(events.nonEmpty)
        assert(nextStep == testNextStep)

      test("a new email creates the user and links this device to them"):
        val emailSurvey = makeSurveyWithEmail(makeInputQuestion())
        val handler = AnswerEmailHandler(
          mockSurveyRepo(emailSurvey = Some(emailSurvey)),
          mockUserRepo(),
          testNextStep)
        val cmd = AnswerEmailCommand(
          answer = AnswerValue.Text("new@example.com"),
          deviceId = TestDevice,
          occurredAt = FixedNow)
        val events = Op.run(handler.handle(cmd)).toOption.get._1
        val created = events.collectFirst { case AppEvent.User(user.Event.UserCreated(id, _, _)) =>
          id
        }
        val linked = events.collectFirst {
          case AppEvent.Device(device.Event.DeviceLinked(userId, deviceId, _)) =>
            (userId, deviceId)
        }
        assert(created.isDefined, linked == created.map(_ -> TestDevice))

      test("an email already answered elsewhere recognizes that user, and links them"):
        val existing = user.Id.generate
        val emailSurvey = makeSurveyWithEmail(makeInputQuestion())
        val handler = AnswerEmailHandler(
          mockSurveyRepo(emailSurvey = Some(emailSurvey)),
          mockUserRepo(knownEmails = Map("known@example.com" -> existing)),
          testNextStep)
        val cmd = AnswerEmailCommand(
          answer = AnswerValue.Text("known@example.com"),
          deviceId = TestDevice,
          occurredAt = FixedNow)
        val events = Op.run(handler.handle(cmd)).toOption.get._1
        assert(
          events.exists {
            case AppEvent.User(user.Event.UserRecognized(id, _, _)) =>
              id == existing
            case _ =>
              false
          },
          !events.exists {
            case AppEvent.User(_: user.Event.UserCreated) =>
              true
            case _ =>
              false
          },
          // Answered when they were first identified; the same email is not answered twice.
          !events.exists {
            case AppEvent.Survey(_: survey.Event.QuestionAnswered) =>
              true
            case _ =>
              false
          },
          events.contains(
            AppEvent.Device(device.Event.DeviceLinked(existing, TestDevice, FixedNow)))
        )

    test("Device policy"):
      import whitelabel.captal.core.device.{Matching, Policy, Signals}
      test("configuration names a policy, and none means the default"):
        assert(
          Policy.fromConfig(None) == Right(Policy.ByMacAndFingerprint),
          Policy.fromConfig(Some("ByMac")) == Right(Policy.ByMac),
          Policy.fromConfig(Some("ByFoo")).isLeft)
      test("each policy compares what it says, and only signals carrying it"):
        val known = Signals("aa:bb", "fp-1")
        assert(
          Matching.ByFingerprint.matches(known, Signals("cc:dd", "fp-1")),
          !Matching.ByMac.matches(known, Signals("cc:dd", "fp-1")),
          Matching.ByMacAndFingerprint.matches(known, Signals("aa:bb", "fp-1")),
          !Matching.ByMacAndFingerprint.matches(known, Signals("aa:bb", "fp-2")),
          !Policy.ByFingerprint.covers(Signals("aa:bb", "")),
          Policy.ByMac.covers(Signals("aa:bb", ""))
        )

    test("AnswerProfilingHandler"):
      test("returns UserNotIdentified when user does not exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val profilingSurvey = makeSurveyWithProfiling(question)
        val surveyRepo = mockSurveyRepo(profilingSurvey = Some(profilingSurvey))
        val userRepo = mockUserRepo()
        val handler = AnswerProfilingHandler(surveyRepo, userRepo, testNextStep)
        val cmd = AnswerProfilingCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.UserNotIdentified =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("succeeds when user and survey exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val profilingSurvey = makeSurveyWithProfiling(question)
        val answeringUser = makeUserAnswering(profilingSurvey.id, question.id)
        val surveyRepo = mockSurveyRepo(profilingSurvey = Some(profilingSurvey))
        val userRepo = mockUserRepo(answeringUser = Some(answeringUser))
        val handler = AnswerProfilingHandler(surveyRepo, userRepo, testNextStep)
        val cmd = AnswerProfilingCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, nextStep) = result.toOption.get
        assert(events.nonEmpty)
        assert(nextStep == testNextStep)

    test("AnswerLocationHandler"):
      test("returns UserNotIdentified when user does not exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val locationSurvey = makeSurveyWithLocation(question)
        val surveyRepo = mockSurveyRepo(locationSurvey = Some(locationSurvey))
        val userRepo = mockUserRepo()
        val handler = AnswerLocationHandler(surveyRepo, userRepo, testNextStep)
        val cmd = AnswerLocationCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.UserNotIdentified =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("succeeds when user and survey exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val locationSurvey = makeSurveyWithLocation(question)
        val answeringUser = makeUserAnswering(locationSurvey.id, question.id)
        val surveyRepo = mockSurveyRepo(locationSurvey = Some(locationSurvey))
        val userRepo = mockUserRepo(answeringUser = Some(answeringUser))
        val handler = AnswerLocationHandler(surveyRepo, userRepo, testNextStep)
        val cmd = AnswerLocationCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = FixedNow)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, nextStep) = result.toOption.get
        assert(events.nonEmpty)
        assert(nextStep == testNextStep)
end HandlerTests
