package whitelabel.captal.core.survey

import java.time.{Instant, LocalDate}

import cats.data.NonEmptyChain
import org.scalacheck.Prop.{forAll, propBoolean}
import org.scalacheck.util.Buildable
import org.scalacheck.{Gen, Prop}
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.generators.*
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.user.{SessionId, State as UserState, User, UserId}

object ValidationFailureTests extends TestSuite:

  // Test fixtures
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

  private def validate(
      questionType: QuestionType,
      value: AnswerValue,
      commonRules: List[CommonRule] = Nil): Either[NonEmptyChain[Error], Unit] =
    val question = makeQuestion(questionType, commonRules)
    val user     = makeUser(question.id)
    val survey   = makeEmailSurvey(question)
    Op.run(ops.answerEmail(user, survey, value, Instant.now)).map(_ => ())

  private def failsWith[E <: Error](
      questionType: QuestionType,
      value: AnswerValue,
      commonRules: List[CommonRule] = Nil)(check: PartialFunction[Error, Boolean]): Boolean =
    validate(questionType, value, commonRules) match
      case Left(errors) =>
        errors.exists(e => check.applyOrElse(e, (_: Error) => false))
      case Right(_) =>
        false

  private def checkProp(prop: Prop): Unit =
    val result = prop.apply(Gen.Parameters.default)
    assert(result.success)

  // Generators for incompatible answer types
  private val genQuestionTypeWithTag: Gen[(QuestionType, String)] =
    val option = QuestionOption(OptionId.generate, LocalizedText("opt", "en"), 1, None)
    Gen.oneOf(
      Gen.const((QuestionType.Radio(List(option)), "radio")),
      Gen.const((QuestionType.Select(List(option)), "select")),
      Gen.const((QuestionType.Checkbox(List(option), Nil), "checkbox")),
      Gen.const((QuestionType.Input(Nil), "input")),
      Gen.const((QuestionType.Rating(Nil), "rating")),
      Gen.const((QuestionType.Numeric(Nil), "numeric")),
      Gen.const((QuestionType.Date(Nil), "date"))
    )

  private def genIncompatibleAnswer(questionTag: String): Gen[AnswerValue] =
    val allAnswers: Map[String, Gen[AnswerValue]] = Map(
      "singleChoice"   -> Gen.const(AnswerValue.SingleChoice(OptionId.generate)),
      "multipleChoice" -> Gen.const(AnswerValue.MultipleChoice(Set(OptionId.generate))),
      "text"           -> Gen.const(AnswerValue.Text("text")),
      "rating"         -> Gen.const(AnswerValue.Rating(5)),
      "numeric"        -> Gen.const(AnswerValue.Numeric(BigDecimal(10))),
      "date"           -> Gen.const(AnswerValue.DateValue(LocalDate.now))
    )
    val compatible: Set[String] =
      questionTag match
        case "radio" | "select" =>
          Set("singleChoice")
        case "checkbox" =>
          Set("multipleChoice")
        case "input" =>
          Set("text")
        case "rating" =>
          Set("rating")
        case "numeric" =>
          Set("numeric")
        case "date" =>
          Set("date")
        case _ =>
          Set.empty
    val incompatible = allAnswers.filterNot((k, _) => compatible.contains(k)).values.toSeq
    Gen.oneOf(incompatible).flatMap(identity)

  private def genStringWithLength(len: Int): Gen[String] = Gen
    .listOfN(len, Gen.alphaChar)
    .map(_.mkString)

  private val genNonDigitString: Gen[String] = Gen
    .alphaStr
    .suchThat(s => s.nonEmpty && !s.forall(_.isDigit))

  val tests = Tests:
    test("SelectionRule failures"):
      test("MinSelections fails when selections < min"):
        checkProp:
          forAll(Gen.chooseNum(2, 5), genQuestionOptions(5)): (minRequired, options) =>
            val tooFew = minRequired - 1
            val rules  = List(SelectionRule.MinSelections(minRequired))
            val qtype  = QuestionType.Checkbox(options, rules)
            val answer = AnswerValue.MultipleChoice(options.take(tooFew).map(_.id).toSet)
            (minRequired >= 2 && options.size >= minRequired) ==>
              failsWith(qtype, answer):
                case Error.TooFewSelections(_, min, actual) =>
                  min == minRequired && actual == tooFew

      test("MaxSelections fails when selections > max"):
        checkProp:
          forAll(Gen.chooseNum(1, 3), genQuestionOptions(5)): (maxAllowed, options) =>
            val tooMany = maxAllowed + 1
            val rules   = List(SelectionRule.MaxSelections(maxAllowed))
            val qtype   = QuestionType.Checkbox(options, rules)
            val answer  = AnswerValue.MultipleChoice(options.take(tooMany).map(_.id).toSet)
            (tooMany <= options.size) ==>
              failsWith(qtype, answer):
                case Error.TooManySelections(_, max, actual) =>
                  max == maxAllowed && actual == tooMany

      test("InvalidOptionSelected fails with unknown option"):
        checkProp:
          forAll(genQuestionOptions(3), genOptionId): (options, invalidId) =>
            val isInvalid = !options.exists(_.id == invalidId)
            val qtype     = QuestionType.Radio(options)
            val answer    = AnswerValue.SingleChoice(invalidId)
            isInvalid ==>
              failsWith(qtype, answer):
                case Error.InvalidOptionSelected(_, optId) =>
                  optId == invalidId

      test("InvalidOptionsSelected fails with unknown options in Checkbox"):
        checkProp:
          forAll(genQuestionOptions(3), genOptionId): (options, invalidId) =>
            val isInvalid = !options.exists(_.id == invalidId)
            val qtype     = QuestionType.Checkbox(options, Nil)
            val answer    = AnswerValue.MultipleChoice(Set(invalidId))
            isInvalid ==>
              failsWith(qtype, answer):
                case Error.InvalidOptionsSelected(_, ids) =>
                  ids.contains(invalidId)

    test("TextRule failures"):
      test("MinLength fails when text.length < min"):
        checkProp:
          forAll(
            Gen.chooseNum(2, 20).flatMap(min => genStringWithLength(min - 1).map(s => (min, s)))):
            (minLen, shortText) =>
              val rules  = List(TextRule.MinLength(minLen))
              val qtype  = QuestionType.Input(rules)
              val answer = AnswerValue.Text(shortText)
              failsWith(qtype, answer):
                case Error.TextTooShort(_, min, actual) =>
                  min == minLen && actual == shortText.length

      test("MaxLength fails when text.length > max"):
        checkProp:
          forAll(
            Gen.chooseNum(1, 10).flatMap(max => genStringWithLength(max + 1).map(s => (max, s)))):
            (maxLen, longText) =>
              val rules  = List(TextRule.MaxLength(maxLen))
              val qtype  = QuestionType.Input(rules)
              val answer = AnswerValue.Text(longText)
              failsWith(qtype, answer):
                case Error.TextTooLong(_, max, actual) =>
                  max == maxLen && actual == longText.length

      test("Email fails with invalid email format"):
        checkProp:
          forAll(genInvalidEmail): invalidEmail =>
            val rules  = List(TextRule.Email)
            val qtype  = QuestionType.Input(rules)
            val answer = AnswerValue.Text(invalidEmail)
            failsWith(qtype, answer):
              case Error.InvalidEmail(_) =>
                true

      test("Url fails with invalid URL format"):
        checkProp:
          forAll(genInvalidUrl): invalidUrl =>
            val rules  = List(TextRule.Url)
            val qtype  = QuestionType.Input(rules)
            val answer = AnswerValue.Text(invalidUrl)
            failsWith(qtype, answer):
              case Error.InvalidUrl(_) =>
                true

      test("Pattern fails when text does not match regex"):
        checkProp:
          forAll(genNonDigitString): nonDigitText =>
            val pattern = "^[0-9]+$"
            val rules   = List(TextRule.Pattern(pattern))
            val qtype   = QuestionType.Input(rules)
            val answer  = AnswerValue.Text(nonDigitText)
            failsWith(qtype, answer):
              case Error.InvalidPattern(_, p) =>
                p == pattern

    test("RangeRule failures"):
      test("Rating Min fails when value < min"):
        checkProp:
          forAll(Gen.chooseNum(5, 10)): minVal =>
            val belowMin = minVal - 1
            val rules    = List(RangeRule.Min(minVal))
            val qtype    = QuestionType.Rating(rules)
            val answer   = AnswerValue.Rating(belowMin)
            failsWith(qtype, answer):
              case Error.RatingOutOfRange(_, min, _, actual) =>
                min == minVal && actual == belowMin

      test("Rating Max fails when value > max"):
        checkProp:
          forAll(Gen.chooseNum(1, 5)): maxVal =>
            val aboveMax = maxVal + 1
            val rules    = List(RangeRule.Max(maxVal))
            val qtype    = QuestionType.Rating(rules)
            val answer   = AnswerValue.Rating(aboveMax)
            failsWith(qtype, answer):
              case Error.RatingOutOfRange(_, _, max, actual) =>
                max == maxVal && actual == aboveMax

      test("Numeric Min fails when value < min"):
        checkProp:
          forAll(Gen.chooseNum(10.0, 100.0)): minVal =>
            val belowMin = BigDecimal(minVal - 1)
            val minBD    = BigDecimal(minVal)
            val rules    = List(RangeRule.Min(minBD))
            val qtype    = QuestionType.Numeric(rules)
            val answer   = AnswerValue.Numeric(belowMin)
            failsWith(qtype, answer):
              case Error.NumericOutOfRange(_, min, _, actual) =>
                min == minBD && actual == belowMin

      test("Numeric Max fails when value > max"):
        checkProp:
          forAll(Gen.chooseNum(1.0, 50.0)): maxVal =>
            val aboveMax = BigDecimal(maxVal + 1)
            val maxBD    = BigDecimal(maxVal)
            val rules    = List(RangeRule.Max(maxBD))
            val qtype    = QuestionType.Numeric(rules)
            val answer   = AnswerValue.Numeric(aboveMax)
            failsWith(qtype, answer):
              case Error.NumericOutOfRange(_, _, max, actual) =>
                max == maxBD && actual == aboveMax

      test("Date Min fails when date < min"):
        val minDate = LocalDate.of(2024, 6, 1)
        checkProp:
          forAll(Gen.chooseNum(1, 365)): daysBefore =>
            val before = minDate.minusDays(daysBefore.toLong)
            val rules  = List(RangeRule.Min(minDate))
            val qtype  = QuestionType.Date(rules)
            val answer = AnswerValue.DateValue(before)
            failsWith(qtype, answer):
              case Error.DateOutOfRange(_, min, _, actual) =>
                min == minDate && actual == before

      test("Date Max fails when date > max"):
        val maxDate = LocalDate.of(2024, 6, 30)
        checkProp:
          forAll(Gen.chooseNum(1, 365)): daysAfter =>
            val after  = maxDate.plusDays(daysAfter.toLong)
            val rules  = List(RangeRule.Max(maxDate))
            val qtype  = QuestionType.Date(rules)
            val answer = AnswerValue.DateValue(after)
            failsWith(qtype, answer):
              case Error.DateOutOfRange(_, _, max, actual) =>
                max == maxDate && actual == after

    test("CommonRule failures"):
      test("Required fails with empty text"):
        checkProp:
          forAll(Gen.const("")): emptyText =>
            val qtype  = QuestionType.Input(Nil)
            val answer = AnswerValue.Text(emptyText)
            failsWith(qtype, answer, List(CommonRule.Required)):
              case Error.RequiredAnswerMissing(_) =>
                true

      test("Required fails with whitespace-only text"):
        checkProp:
          forAll(Gen.chooseNum(1, 10)): spaces =>
            val whitespace = " " * spaces
            val qtype      = QuestionType.Input(Nil)
            val answer     = AnswerValue.Text(whitespace)
            failsWith(qtype, answer, List(CommonRule.Required)):
              case Error.RequiredAnswerMissing(_) =>
                true

      test("Required fails with empty MultipleChoice"):
        checkProp:
          forAll(genQuestionOptions(3)): options =>
            val qtype  = QuestionType.Checkbox(options, Nil)
            val answer = AnswerValue.MultipleChoice(Set.empty)
            failsWith(qtype, answer, List(CommonRule.Required)):
              case Error.RequiredAnswerMissing(_) =>
                true

    test("IncompatibleAnswerType failures"):
      test("mismatched question and answer types fail"):
        checkProp:
          val gen =
            for
              (qtype, tag) <- genQuestionTypeWithTag
              answer       <- genIncompatibleAnswer(tag)
            yield (qtype, answer)
          forAll(gen): (qtype, answer) =>
            failsWith(qtype, answer):
              case Error.IncompatibleAnswerType(_, _) =>
                true
