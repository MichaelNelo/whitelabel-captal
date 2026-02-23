package whitelabel.captal.core.application

import java.time.Instant

import cats.{Id, Parallel}
import utest.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{Session, SessionData, SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.{AdvertiserId, State as SurveyState, Survey}
import whitelabel.captal.core.user.{DeviceId, SessionId, State as UserState, User}
import whitelabel.captal.core.{Op, survey, user}

object HandlerTests extends TestSuite:

  // Simple mock implementations using cats.Id
  private def makeRadioQuestion(optionId: OptionId): QuestionToAnswer = QuestionToAnswer(
    id = survey.question.Id.generate,
    text = LocalizedText("Test question", "en"),
    description = None,
    questionType = QuestionType.Radio(
      List(QuestionOption(optionId, LocalizedText("Opt", "en"), 1, None))),
    commonRules = Nil,
    pointsAwarded = 10
  )

  private def makeInputQuestion(): QuestionToAnswer = QuestionToAnswer(
    id = survey.question.Id.generate,
    text = LocalizedText("Enter your email", "en"),
    description = None,
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

  private def makeSurveyWithAdvertiser(
      question: QuestionToAnswer): Survey[SurveyState.WithAdvertiserQuestion] = Survey(
    survey.Id.generate,
    SurveyState.WithAdvertiserQuestion(AdvertiserId.generate, question))

  private def makeUserAnswering(questionId: survey.question.Id): User[UserState.AnsweringQuestion] =
    User(user.Id.generate, UserState.AnsweringQuestion(SessionId.generate, "en", questionId))

  // Mock Session
  private def mockSession(
      puserId: user.Id,
      surveyId: Option[survey.Id] = None,
      questionId: Option[survey.question.Id] = None): Session[Id] =
    new Session[Id]:
      def getSessionData: Id[SessionData] =
        SessionData(SessionId.generate, puserId, "en", surveyId, questionId)
      def setCurrentSurvey(sId: survey.Id, qId: survey.question.Id): Id[Unit] = ()

  // Mock SurveyRepository
  private def mockSurveyRepo(
      emailSurvey: Option[Survey[SurveyState.WithEmailQuestion]] = None,
      profilingSurvey: Option[Survey[SurveyState.WithProfilingQuestion]] = None,
      locationSurvey: Option[Survey[SurveyState.WithLocationQuestion]] = None,
      advertiserSurvey: Option[Survey[SurveyState.WithAdvertiserQuestion]] = None)
      : SurveyRepository[Id] =
    new SurveyRepository[Id]:
      def findById(id: survey.Id) = None
      def findWithEmailQuestion(id: survey.Id, qId: survey.question.Id) = emailSurvey.filter(s =>
        s.id == id && s.state.question.id == qId)
      def findWithProfilingQuestion(id: survey.Id, qId: survey.question.Id) = profilingSurvey
        .filter(s => s.id == id && s.state.question.id == qId)
      def findWithLocationQuestion(id: survey.Id, qId: survey.question.Id) = locationSurvey.filter(
        s => s.id == id && s.state.question.id == qId)
      def findWithAdvertiserQuestion(id: survey.Id, qId: survey.question.Id) = advertiserSurvey
        .filter(s => s.id == id && s.state.question.id == qId)
      def findNextIdentificationSurvey(userId: user.Id) = None

  // Mock UserRepository
  private def mockUserRepo(
      answeringUser: Option[User[UserState.AnsweringQuestion]] = None,
      expectedSurveyQuestionId: Option[survey.question.Id] = None): UserRepository[Id] =
    new UserRepository[Id]:
      def findById(id: user.Id) = None
      def findAnswering(id: user.Id, questionId: survey.question.Id) = answeringUser.filter(u =>
        u.id == id && expectedSurveyQuestionId.forall(_ == questionId))

  given Parallel[Id] = Parallel.identity[Id]

  val tests = Tests:

    test("AnswerEmailHandler"):
      test("returns NoSurveyAssigned when session has no survey"):
        val session = mockSession(user.Id.generate)
        val surveyRepo = mockSurveyRepo()
        val handler = AnswerEmailHandler(session, surveyRepo)
        val cmd = AnswerEmailCommand(
          sessionId = SessionId.generate,
          deviceId = DeviceId("device-1"),
          locale = "en",
          answer = AnswerValue.Text("user@example.com"),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(errs.exists {
              case Error.NoSurveyAssigned => true
              case _                      => false
            })
          case Right(_) =>
            assert(false)

      test("returns InvalidEmailFormat when answer is not Text"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val emailSurvey = makeSurveyWithEmail(question)
        val session = mockSession(
          user.Id.generate,
          surveyId = Some(emailSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(session, surveyRepo)
        val cmd = AnswerEmailCommand(
          sessionId = SessionId.generate,
          deviceId = DeviceId("device-1"),
          locale = "en",
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(errs.exists {
              case Error.InvalidEmailFormat(_) => true
              case _                           => false
            })
          case Right(_) =>
            assert(false)

      test("returns InvalidEmailFormat when email format is invalid"):
        val question = makeInputQuestion()
        val emailSurvey = makeSurveyWithEmail(question)
        val session = mockSession(
          user.Id.generate,
          surveyId = Some(emailSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(session, surveyRepo)
        val cmd = AnswerEmailCommand(
          sessionId = SessionId.generate,
          deviceId = DeviceId("device-1"),
          locale = "en",
          answer = AnswerValue.Text("not-an-email"),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(errs.exists {
              case Error.InvalidEmailFormat(_) => true
              case _                           => false
            })
          case Right(_) =>
            assert(false)

      test("succeeds with valid email"):
        val question = makeInputQuestion()
        val emailSurvey = makeSurveyWithEmail(question)
        val session = mockSession(
          user.Id.generate,
          surveyId = Some(emailSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(session, surveyRepo)
        val cmd = AnswerEmailCommand(
          sessionId = SessionId.generate,
          deviceId = DeviceId("device-1"),
          locale = "en",
          answer = AnswerValue.Text("user@example.com"),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, answer) = result.toOption.get
        assert(events.nonEmpty)
        assert(answer.questionId == question.id)

    test("AnswerProfilingHandler"):
      test("returns NoSurveyAssigned when session has no survey"):
        val userId = user.Id.generate
        val session = mockSession(userId)
        val surveyRepo = mockSurveyRepo()
        val userRepo = mockUserRepo()
        val handler = AnswerProfilingHandler(session, surveyRepo, userRepo)
        val cmd = AnswerProfilingCommand(
          answer = AnswerValue.SingleChoice(OptionId.generate),
          occurredAt = Instant.now)
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

      test("returns UserNotFound when user does not exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val profilingSurvey = makeSurveyWithProfiling(question)
        val userId = user.Id.generate
        val session = mockSession(
          userId,
          surveyId = Some(profilingSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(profilingSurvey = Some(profilingSurvey))
        val userRepo = mockUserRepo()
        val handler = AnswerProfilingHandler(session, surveyRepo, userRepo)
        val cmd = AnswerProfilingCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.UserNotFound(_) =>
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
        val answeringUser = makeUserAnswering(question.id)
        val session = mockSession(
          answeringUser.id,
          surveyId = Some(profilingSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(profilingSurvey = Some(profilingSurvey))
        val userRepo = mockUserRepo(Some(answeringUser), Some(question.id))
        val handler = AnswerProfilingHandler(session, surveyRepo, userRepo)
        val cmd = AnswerProfilingCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, answer) = result.toOption.get
        assert(events.nonEmpty)
        assert(answer.questionId == question.id)

    test("AnswerLocationHandler"):
      test("returns NoSurveyAssigned when session has no survey"):
        val userId = user.Id.generate
        val session = mockSession(userId)
        val surveyRepo = mockSurveyRepo()
        val userRepo = mockUserRepo()
        val handler = AnswerLocationHandler(session, surveyRepo, userRepo)
        val cmd = AnswerLocationCommand(
          answer = AnswerValue.SingleChoice(OptionId.generate),
          occurredAt = Instant.now)
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

      test("returns UserNotFound when user does not exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val locationSurvey = makeSurveyWithLocation(question)
        val userId = user.Id.generate
        val session = mockSession(
          userId,
          surveyId = Some(locationSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(locationSurvey = Some(locationSurvey))
        val userRepo = mockUserRepo()
        val handler = AnswerLocationHandler(session, surveyRepo, userRepo)
        val cmd = AnswerLocationCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errs) =>
            assert(
              errs.exists {
                case Error.UserNotFound(_) =>
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
        val answeringUser = makeUserAnswering(question.id)
        val session = mockSession(
          answeringUser.id,
          surveyId = Some(locationSurvey.id),
          questionId = Some(question.id))
        val surveyRepo = mockSurveyRepo(locationSurvey = Some(locationSurvey))
        val userRepo = mockUserRepo(Some(answeringUser), Some(question.id))
        val handler = AnswerLocationHandler(session, surveyRepo, userRepo)
        val cmd = AnswerLocationCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, answer) = result.toOption.get
        assert(events.nonEmpty)
        assert(answer.questionId == question.id)

    test("AnswerAdvertiserHandler"):
      val userId = user.Id.generate

      test("returns UserNotFound when user does not exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val surveyData = makeSurveyWithAdvertiser(question)
        val session = mockSession(userId)
        val surveyRepo = mockSurveyRepo(advertiserSurvey = Some(surveyData))
        val userRepo = mockUserRepo()
        val handler = AnswerAdvertiserHandler(session, surveyRepo, userRepo)
        val cmd = AnswerAdvertiserCommand(
          surveyId = surveyData.id,
          questionId = question.id,
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errors) =>
            assert(
              errors.exists {
                case Error.UserNotFound(_) =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("returns SurveyNotFound when survey does not exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val userData = makeUserAnswering(question.id)
        val session = mockSession(userData.id)
        val surveyRepo = mockSurveyRepo()
        val userRepo = mockUserRepo(Some(userData), Some(question.id))
        val handler = AnswerAdvertiserHandler(session, surveyRepo, userRepo)
        val cmd = AnswerAdvertiserCommand(
          surveyId = survey.Id.generate,
          questionId = question.id,
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        result match
          case Left(errors) =>
            assert(
              errors.exists {
                case Error.SurveyNotFound(_) =>
                  true
                case _ =>
                  false
              })
          case Right(_) =>
            assert(false)

      test("succeeds when user and survey exist"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val surveyData = makeSurveyWithAdvertiser(question)
        val userData = makeUserAnswering(question.id)
        val session = mockSession(userData.id)
        val surveyRepo = mockSurveyRepo(advertiserSurvey = Some(surveyData))
        val userRepo = mockUserRepo(Some(userData), Some(question.id))
        val handler = AnswerAdvertiserHandler(session, surveyRepo, userRepo)
        val cmd = AnswerAdvertiserCommand(
          surveyId = surveyData.id,
          questionId = question.id,
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, answer) = result.toOption.get
        assert(events.nonEmpty)
        assert(answer.questionId == question.id)
end HandlerTests
