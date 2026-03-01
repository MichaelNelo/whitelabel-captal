package whitelabel.captal.core.application

import java.time.Instant

import cats.{Id, Parallel}
import utest.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{SurveyRepository, UserRepository}
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.{AdvertiserId, State as SurveyState, Survey}
import whitelabel.captal.core.user.{State as UserState, User}
import whitelabel.captal.core.{Op, survey, user}

// Test fixture for NextStep
private val testNextStep = NextStep(Phase.Ready)

object HandlerTests extends TestSuite:

  // Simple mock implementations using cats.Id
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

  private def makeSurveyWithAdvertiser(
      question: QuestionToAnswer): Survey[SurveyState.WithAdvertiserQuestion] = Survey(
    survey.Id.generate,
    SurveyState.WithAdvertiserQuestion(AdvertiserId.generate, question))

  private def makeUserAnswering(
      surveyId: survey.Id,
      questionId: survey.question.Id): User[UserState.AnsweringQuestion] = User(
    user.Id.generate,
    UserState.AnsweringQuestion(surveyId, questionId))

  // Mock SurveyRepository
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

  // Mock UserRepository
  private def mockUserRepo(
      answeringUser: Option[User[UserState.AnsweringQuestion]] = None,
      withEmailUser: Option[User[UserState.WithEmail]] = None): UserRepository[Id] =
    new UserRepository[Id]:
      def findWithEmail() = withEmailUser
      def findAnswering() = answeringUser

  given Parallel[Id] = Parallel.identity[Id]

  val tests = Tests:

    test("AnswerEmailHandler"):
      test("returns NoSurveyAssigned when no email survey assigned"):
        val surveyRepo = mockSurveyRepo()
        val handler = AnswerEmailHandler(surveyRepo, testNextStep)
        val cmd = AnswerEmailCommand(
          answer = AnswerValue.Text("user@example.com"),
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

      test("returns InvalidEmailFormat when answer is not Text"):
        val optionId = OptionId.generate
        val question = makeRadioQuestion(optionId)
        val emailSurvey = makeSurveyWithEmail(question)
        val surveyRepo = mockSurveyRepo(emailSurvey = Some(emailSurvey))
        val handler = AnswerEmailHandler(surveyRepo, testNextStep)
        val cmd = AnswerEmailCommand(
          answer = AnswerValue.SingleChoice(optionId),
          occurredAt = Instant.now)
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
        val handler = AnswerEmailHandler(surveyRepo, testNextStep)
        val cmd = AnswerEmailCommand(
          answer = AnswerValue.Text("not-an-email"),
          occurredAt = Instant.now)
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
        val handler = AnswerEmailHandler(surveyRepo, testNextStep)
        val cmd = AnswerEmailCommand(
          answer = AnswerValue.Text("user@example.com"),
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, nextStep) = result.toOption.get
        assert(events.nonEmpty)
        assert(nextStep == testNextStep)

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
          occurredAt = Instant.now)
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
          occurredAt = Instant.now)
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
          occurredAt = Instant.now)
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
          occurredAt = Instant.now)
        val result = Op.run(handler.handle(cmd))
        assert(result.isRight)
        val (events, nextStep) = result.toOption.get
        assert(events.nonEmpty)
        assert(nextStep == testNextStep)
end HandlerTests
