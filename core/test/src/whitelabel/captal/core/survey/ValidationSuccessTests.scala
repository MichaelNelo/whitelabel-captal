package whitelabel.captal.core.survey

import java.time.{Instant, LocalDate}

import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.user.{SessionId, State as UserState, User, UserId}

object ValidationSuccessTests extends TestSuite:
  // Test fixtures using public API
  private def makeUser(questionId: QuestionId): User[UserState.AnsweringQuestion] = User(
    UserId.generate,
    UserState.AnsweringQuestion(SessionId.generate, "en", questionId))
  private def makeEmailSurvey(question: QuestionToAnswer): Survey[State.WithEmailQuestion] = Survey(
    SurveyId.generate,
    State.WithEmailQuestion(question))
  private def makeQuestion(
      questionType: QuestionType,
      commonRules: List[CommonRule] = Nil): QuestionToAnswer = QuestionToAnswer(
    id = QuestionId.generate,
    text = LocalizedText("Test question", "en"),
    description = None,
    questionType = questionType,
    commonRules = commonRules,
    pointsAwarded = 10)
  private def validateSucceeds(
      questionType: QuestionType,
      value: AnswerValue,
      commonRules: List[CommonRule] = Nil): Boolean =
    val question = makeQuestion(questionType, commonRules)
    val user     = makeUser(question.id)
    val survey   = makeEmailSurvey(question)
    val result   = Op.run(ops.answerEmail(user, survey, value, Instant.now))
    result.isRight
  private def validateReturnsNoErrors(
      questionType: QuestionType,
      value: AnswerValue,
      commonRules: List[CommonRule] = Nil): Boolean =
    val question = makeQuestion(questionType, commonRules)
    val user     = makeUser(question.id)
    val survey   = makeEmailSurvey(question)
    val result   = Op.run(ops.answerEmail(user, survey, value, Instant.now))
    result match
      case Right((events, _)) =>
        events.nonEmpty // answerEmail emits an event on success
      case Left(_) =>
        false

  val tests = Tests:
    test("Radio question"):
      test("valid option selection succeeds"):
        val optionId = OptionId.generate
        val options  = List(QuestionOption(optionId, LocalizedText("Option 1", "en"), 1, None))
        val qtype    = QuestionType.Radio(options)
        val answer   = AnswerValue.SingleChoice(optionId)
        assert(validateSucceeds(qtype, answer))

    test("Select question"):
      test("valid option selection succeeds"):
        val optionId = OptionId.generate
        val options  = List(QuestionOption(optionId, LocalizedText("Option A", "en"), 1, None))
        val qtype    = QuestionType.Select(options)
        val answer   = AnswerValue.SingleChoice(optionId)
        assert(validateSucceeds(qtype, answer))

    test("Checkbox question"):
      test("valid selections within min/max succeeds"):
        val option1 = QuestionOption(OptionId.generate, LocalizedText("Opt 1", "en"), 1, None)
        val option2 = QuestionOption(OptionId.generate, LocalizedText("Opt 2", "en"), 2, None)
        val option3 = QuestionOption(OptionId.generate, LocalizedText("Opt 3", "en"), 3, None)
        val options = List(option1, option2, option3)
        val rules   = List(SelectionRule.MinSelections(1), SelectionRule.MaxSelections(3))
        val qtype   = QuestionType.Checkbox(options, rules)
        val answer  = AnswerValue.MultipleChoice(Set(option1.id, option2.id))
        assert(validateSucceeds(qtype, answer))
      test("exactly min selections succeeds"):
        val option1 = QuestionOption(OptionId.generate, LocalizedText("Opt 1", "en"), 1, None)
        val option2 = QuestionOption(OptionId.generate, LocalizedText("Opt 2", "en"), 2, None)
        val options = List(option1, option2)
        val rules   = List(SelectionRule.MinSelections(2))
        val qtype   = QuestionType.Checkbox(options, rules)
        val answer  = AnswerValue.MultipleChoice(Set(option1.id, option2.id))
        assert(validateSucceeds(qtype, answer))
      test("exactly max selections succeeds"):
        val option1 = QuestionOption(OptionId.generate, LocalizedText("Opt 1", "en"), 1, None)
        val option2 = QuestionOption(OptionId.generate, LocalizedText("Opt 2", "en"), 2, None)
        val options = List(option1, option2)
        val rules   = List(SelectionRule.MaxSelections(2))
        val qtype   = QuestionType.Checkbox(options, rules)
        val answer  = AnswerValue.MultipleChoice(Set(option1.id, option2.id))
        assert(validateSucceeds(qtype, answer))

    test("Input question"):
      test("text within length bounds succeeds"):
        val rules  = List(TextRule.MinLength(3), TextRule.MaxLength(10))
        val qtype  = QuestionType.Input(rules)
        val answer = AnswerValue.Text("hello")
        assert(validateSucceeds(qtype, answer))
      test("valid email succeeds"):
        val rules  = List(TextRule.Email)
        val qtype  = QuestionType.Input(rules)
        val answer = AnswerValue.Text("user@example.com")
        assert(validateSucceeds(qtype, answer))
      test("valid URL succeeds"):
        val rules  = List(TextRule.Url)
        val qtype  = QuestionType.Input(rules)
        val answer = AnswerValue.Text("https://example.com/path")
        assert(validateSucceeds(qtype, answer))
      test("text matching pattern succeeds"):
        val rules  = List(TextRule.Pattern("^[A-Z]{3}-[0-9]{4}$"))
        val qtype  = QuestionType.Input(rules)
        val answer = AnswerValue.Text("ABC-1234")
        assert(validateSucceeds(qtype, answer))

    test("Rating question"):
      test("value within range succeeds"):
        val rules  = List(RangeRule.Min(1), RangeRule.Max(5))
        val qtype  = QuestionType.Rating(rules)
        val answer = AnswerValue.Rating(3)
        assert(validateSucceeds(qtype, answer))
      test("value at min boundary succeeds"):
        val rules  = List(RangeRule.Min(1), RangeRule.Max(10))
        val qtype  = QuestionType.Rating(rules)
        val answer = AnswerValue.Rating(1)
        assert(validateSucceeds(qtype, answer))
      test("value at max boundary succeeds"):
        val rules  = List(RangeRule.Min(1), RangeRule.Max(10))
        val qtype  = QuestionType.Rating(rules)
        val answer = AnswerValue.Rating(10)
        assert(validateSucceeds(qtype, answer))

    test("Numeric question"):
      test("value within range succeeds"):
        val rules  = List(RangeRule.Min(BigDecimal(0)), RangeRule.Max(BigDecimal(100)))
        val qtype  = QuestionType.Numeric(rules)
        val answer = AnswerValue.Numeric(BigDecimal(50.5))
        assert(validateSucceeds(qtype, answer))
      test("decimal value at boundaries succeeds"):
        val rules  = List(RangeRule.Min(BigDecimal(0.01)), RangeRule.Max(BigDecimal(99.99)))
        val qtype  = QuestionType.Numeric(rules)
        val answer = AnswerValue.Numeric(BigDecimal(0.01))
        assert(validateSucceeds(qtype, answer))

    test("Date question"):
      test("date within range succeeds"):
        val minDate = LocalDate.of(2024, 1, 1)
        val maxDate = LocalDate.of(2024, 12, 31)
        val rules   = List(RangeRule.Min(minDate), RangeRule.Max(maxDate))
        val qtype   = QuestionType.Date(rules)
        val answer  = AnswerValue.DateValue(LocalDate.of(2024, 6, 15))
        assert(validateSucceeds(qtype, answer))
      test("date at min boundary succeeds"):
        val minDate = LocalDate.of(2024, 1, 1)
        val rules   = List(RangeRule.Min(minDate))
        val qtype   = QuestionType.Date(rules)
        val answer  = AnswerValue.DateValue(minDate)
        assert(validateSucceeds(qtype, answer))
      test("date at max boundary succeeds"):
        val maxDate = LocalDate.of(2024, 12, 31)
        val rules   = List(RangeRule.Max(maxDate))
        val qtype   = QuestionType.Date(rules)
        val answer  = AnswerValue.DateValue(maxDate)
        assert(validateSucceeds(qtype, answer))

    test("Required rule"):
      test("non-empty text passes Required"):
        val qtype  = QuestionType.Input(Nil)
        val answer = AnswerValue.Text("valid text")
        assert(validateSucceeds(qtype, answer, List(CommonRule.Required)))
      test("non-empty MultipleChoice passes Required"):
        val option1 = QuestionOption(OptionId.generate, LocalizedText("Opt", "en"), 1, None)
        val qtype   = QuestionType.Checkbox(List(option1), Nil)
        val answer  = AnswerValue.MultipleChoice(Set(option1.id))
        assert(validateSucceeds(qtype, answer, List(CommonRule.Required)))
      test("SingleChoice passes Required"):
        val option1 = QuestionOption(OptionId.generate, LocalizedText("Opt", "en"), 1, None)
        val qtype   = QuestionType.Radio(List(option1))
        val answer  = AnswerValue.SingleChoice(option1.id)
        assert(validateSucceeds(qtype, answer, List(CommonRule.Required)))

    test("Events emitted on successful answer"):
      test("successful validation emits QuestionAnswered event"):
        val optionId = OptionId.generate
        val options  = List(QuestionOption(optionId, LocalizedText("Opt", "en"), 1, None))
        val qtype    = QuestionType.Radio(options)
        val answer   = AnswerValue.SingleChoice(optionId)
        assert(validateReturnsNoErrors(qtype, answer))
