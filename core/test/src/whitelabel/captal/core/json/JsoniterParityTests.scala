package whitelabel.captal.core.json

import java.time.LocalDate

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import io.circe.syntax.*
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}
import utest.*
import whitelabel.captal.core.generators.*
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.survey.question.jsoniter.given

/** The migration off circe is only safe if the bytes do not move.
  *
  * `answers.answer_value` and the event log store these types as JSON, written and never read back,
  * so a format change would not break the application — it would quietly split the audit trail in
  * two, with rows before the change in one shape and rows after in another. And the wire format is
  * shared with a client that ships separately from the server.
  *
  * So rather than trust that two derivations agree, this compares them over generated values:
  * whatever circe produces today, jsoniter must produce exactly.
  */
object JsoniterParityTests extends TestSuite:

  private def checkProp(prop: Prop): Unit =
    val result = prop.apply(Gen.Parameters.default)
    assert(result.success)

  private def parity[A: io.circe.Encoder: JsonValueCodec](gen: Gen[A]): Prop =
    forAll(gen)(a => writeToString(a) == a.asJson.noSpaces)

  private val options = List(
    QuestionOption(OptionId.generate, LocalizedText("uno", "es"), 1, None),
    QuestionOption(OptionId.generate, LocalizedText("dos", "es"), 2, None))

  /** Not in `generators`, because nothing else needed them: the other suites build rules by hand
    * for the case under test. Parity has to cover every branch, including the two that carry no
    * `"value"` at all.
    */
  private val genTextRule: Gen[TextRule] = Gen.oneOf(
    Gen.chooseNum(0, 100).map(TextRule.MinLength.apply),
    Gen.chooseNum(0, 100).map(TextRule.MaxLength.apply),
    Gen.alphaNumStr.map(TextRule.Pattern.apply),
    Gen.const(TextRule.Email),
    Gen.const(TextRule.Url)
  )

  private val genHierarchyLevel: Gen[HierarchyLevel] = Gen.oneOf(
    HierarchyLevel.State,
    HierarchyLevel.City,
    HierarchyLevel.Municipality,
    HierarchyLevel.Urbanization)

  private val genLocalizedText: Gen[LocalizedText] =
    for
      v <- Gen.alphaNumStr
      l <- Gen.oneOf("es", "en")
    yield LocalizedText(v, l)

  /** Both with and without the optional parent, because circe writes `null` where jsoniter would
    * rather omit the field — the config turning that off is what this checks.
    */
  private val genOption: Gen[QuestionOption] =
    for
      text   <- genLocalizedText
      order  <- Gen.chooseNum(1, 20)
      parent <- Gen.option(Gen.const(OptionId.generate))
    yield QuestionOption(OptionId.generate, text, order, parent)

  private val genQuestionType: Gen[QuestionType] =
    for
      opts <- Gen.listOfN(2, genOption)
      sel  <- Gen.listOfN(1, genSelectionRule)
      txt  <- Gen.listOfN(1, genTextRule)
      ints <- Gen.listOfN(1, genRangeRuleInt)
      dec  <- Gen.listOfN(1, genRangeRuleBigDecimal)
      dat  <- Gen.listOfN(1, genRangeRuleLocalDate)
      t    <- Gen.oneOf[QuestionType](
        QuestionType.Radio(opts),
        QuestionType.Checkbox(opts, sel),
        QuestionType.Select(opts),
        QuestionType.Input(txt),
        QuestionType.Rating(ints),
        QuestionType.Numeric(dec),
        QuestionType.Date(dat)
      )
    yield t

  val tests = Tests:
    test("LocalizedText") - checkProp(parity(genLocalizedText))
    test("QuestionOption") - checkProp(parity(genOption))
    test("QuestionType") - checkProp(parity(genQuestionType))
    test("SelectionRule") - checkProp(parity(genSelectionRule))
    test("TextRule") - checkProp(parity(genTextRule))
    test("CommonRule") - checkProp(parity(genCommonRule))
    test("HierarchyLevel") - checkProp(parity(genHierarchyLevel))
    test("RangeRule[Int]") - checkProp(parity(genRangeRuleInt))
    test("RangeRule[BigDecimal]") - checkProp(parity(genRangeRuleBigDecimal))
    test("RangeRule[LocalDate]") - checkProp(parity(genRangeRuleLocalDate))
    test("AnswerValue.SingleChoice") - checkProp(parity[AnswerValue](genSingleChoice(options)))
    test("AnswerValue.MultipleChoice") -
      checkProp(parity[AnswerValue](genMultipleChoice(options, 2)))
    test("AnswerValue.Text") - checkProp(parity[AnswerValue](genTextAnswerWithLength(0, 40)))
    test("AnswerValue.Rating") - checkProp(parity[AnswerValue](genRatingAnswerInRange(0f, 10f)))
    test("AnswerValue.Numeric") -
      checkProp(parity[AnswerValue](genNumericAnswerInRange(BigDecimal(-1000), BigDecimal(1000))))
    test("AnswerValue.DateValue") -
      checkProp(
        parity[AnswerValue](
          genDateAnswerInRange(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1))))
end JsoniterParityTests
