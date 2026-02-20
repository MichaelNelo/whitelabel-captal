package whitelabel.captal.core

import java.time.LocalDate
import java.util.UUID

import org.scalacheck.Gen
import whitelabel.captal.core.survey.question.*

object generators:

  // Basic generators
  val genUUID: Gen[UUID] = Gen.uuid

  val genQuestionId: Gen[QuestionId] = genUUID.map(QuestionId.apply)

  val genOptionId: Gen[OptionId] = genUUID.map(OptionId.apply)

  val genLocalizedText: Gen[LocalizedText] =
    for
      text   <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      locale <- Gen.oneOf("en", "es", "pt")
    yield LocalizedText(text, locale)

  val genQuestionOption: Gen[QuestionOption] =
    for
      id           <- genOptionId
      text         <- genLocalizedText
      displayOrder <- Gen.posNum[Int]
    yield QuestionOption(id, text, displayOrder, None)

  def genQuestionOptions(n: Int): Gen[List[QuestionOption]] = Gen.listOfN(n, genQuestionOption)

  // Rule generators
  val genCommonRule: Gen[CommonRule] = Gen.const(CommonRule.Required)

  val genSelectionRuleMin: Gen[SelectionRule] = Gen
    .posNum[Int]
    .map(SelectionRule.MinSelections.apply)

  val genSelectionRuleMax: Gen[SelectionRule] = Gen
    .posNum[Int]
    .map(SelectionRule.MaxSelections.apply)

  val genSelectionRule: Gen[SelectionRule] = Gen.oneOf(genSelectionRuleMin, genSelectionRuleMax)

  val genTextRuleMinLength: Gen[TextRule] = Gen.posNum[Int].map(TextRule.MinLength.apply)

  val genTextRuleMaxLength: Gen[TextRule] = Gen.posNum[Int].map(TextRule.MaxLength.apply)

  val genTextRule: Gen[TextRule] = Gen.oneOf(
    genTextRuleMinLength,
    genTextRuleMaxLength,
    Gen.const(TextRule.Email),
    Gen.const(TextRule.Url))

  def genRangeRuleInt: Gen[RangeRule[Int]] = Gen.oneOf(
    Gen.chooseNum(-100, 100).map(RangeRule.Min.apply),
    Gen.chooseNum(-100, 100).map(RangeRule.Max.apply))

  def genRangeRuleBigDecimal: Gen[RangeRule[BigDecimal]] = Gen.oneOf(
    Gen.chooseNum(-1000.0, 1000.0).map(d => RangeRule.Min(BigDecimal(d))),
    Gen.chooseNum(-1000.0, 1000.0).map(d => RangeRule.Max(BigDecimal(d))))

  def genRangeRuleLocalDate: Gen[RangeRule[LocalDate]] =
    val baseDate = LocalDate.of(2024, 1, 1)
    Gen.oneOf(
      Gen.chooseNum(-365, 365).map(d => RangeRule.Min(baseDate.plusDays(d.toLong))),
      Gen.chooseNum(-365, 365).map(d => RangeRule.Max(baseDate.plusDays(d.toLong)))
    )

  // AnswerValue generators
  def genSingleChoice(validOptions: List[QuestionOption]): Gen[AnswerValue.SingleChoice] = Gen
    .oneOf(validOptions)
    .map(opt => AnswerValue.SingleChoice(opt.id))

  def genMultipleChoice(
      validOptions: List[QuestionOption],
      count: Int): Gen[AnswerValue.MultipleChoice] = Gen
    .pick(count.min(validOptions.size), validOptions)
    .map(opts => AnswerValue.MultipleChoice(opts.map(_.id).toSet))

  val genTextAnswer: Gen[AnswerValue.Text] = Gen.alphaNumStr.map(AnswerValue.Text.apply)

  def genTextAnswerWithLength(minLen: Int, maxLen: Int): Gen[AnswerValue.Text] = Gen
    .chooseNum(minLen, maxLen)
    .flatMap { len =>
      Gen.listOfN(len, Gen.alphaChar).map(_.mkString).map(AnswerValue.Text.apply)
    }

  val genRatingAnswer: Gen[AnswerValue.Rating] = Gen
    .chooseNum(1f, 10f)
    .map(AnswerValue.Rating.apply)

  def genRatingAnswerInRange(min: Float, max: Float): Gen[AnswerValue.Rating] = Gen
    .chooseNum(min, max)
    .map(AnswerValue.Rating.apply)

  val genNumericAnswer: Gen[AnswerValue.Numeric] = Gen
    .chooseNum(-1000.0, 1000.0)
    .map(d => AnswerValue.Numeric(BigDecimal(d)))

  def genNumericAnswerInRange(min: BigDecimal, max: BigDecimal): Gen[AnswerValue.Numeric] = Gen
    .chooseNum(min.toDouble, max.toDouble)
    .map(d => AnswerValue.Numeric(BigDecimal(d)))

  val genDateAnswer: Gen[AnswerValue.DateValue] =
    val baseDate = LocalDate.of(2024, 1, 1)
    Gen.chooseNum(-365, 365).map(d => AnswerValue.DateValue(baseDate.plusDays(d.toLong)))

  def genDateAnswerInRange(min: LocalDate, max: LocalDate): Gen[AnswerValue.DateValue] =
    val days = java.time.temporal.ChronoUnit.DAYS.between(min, max).toInt
    Gen.chooseNum(0, days).map(d => AnswerValue.DateValue(min.plusDays(d.toLong)))

  // Invalid generators (for failure tests)
  def genInvalidOptionId(validOptions: List[QuestionOption]): Gen[OptionId] = genOptionId.suchThat(
    id => !validOptions.exists(_.id == id))

  val genInvalidEmail: Gen[String] = Gen.oneOf(
    Gen.alphaNumStr.suchThat(s => s.nonEmpty && !s.contains("@")),
    Gen.const("invalid"),
    Gen.const("no-at-sign.com"),
    Gen.const("@missing-local.com"),
    Gen.const("missing-domain@")
  )

  val genValidEmail: Gen[String] =
    for
      local  <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      domain <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      tld    <- Gen.oneOf("com", "org", "net", "io")
    yield s"$local@$domain.$tld"

  val genInvalidUrl: Gen[String] = Gen.oneOf(
    Gen.alphaNumStr.suchThat(s => s.nonEmpty && !s.startsWith("http")),
    Gen.const("not-a-url"),
    Gen.const("ftp://wrong-protocol.com"),
    Gen.const("http://"),
    Gen.const("https://")
  )

  val genValidUrl: Gen[String] =
    for
      protocol <- Gen.oneOf("http", "https")
      domain   <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      tld      <- Gen.oneOf("com", "org", "net")
      path     <- Gen.option(Gen.alphaNumStr.map("/" + _))
    yield s"$protocol://$domain.$tld${path.getOrElse("")}"
