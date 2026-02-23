package whitelabel.captal.core.survey.question

import java.time.LocalDate

import cats.Traverse
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.Op.given
import whitelabel.captal.core.survey.{Error, Event as SurveyEvent}

object ops:
  type RuleOp[A] = Op[SurveyEvent, Error, A]

  def validate(
      questionId: Id,
      commonRules: List[CommonRule],
      questionType: QuestionType,
      value: AnswerValue): RuleOp[Unit] =
    val commonRulesResult = commonRules.validate(questionId, value)
    val questionTypeResult = questionType.validate(questionId, value)
    (commonRulesResult, questionTypeResult).parTupled.void

  extension (questionType: QuestionType)
    def validate(questionId: Id, value: AnswerValue): RuleOp[Unit] =
      (questionType, value) match
        case (QuestionType.Radio(options), AnswerValue.SingleChoice(optionId)) =>
          Op.failUnless(
            options.exists(_.id == optionId),
            Error.InvalidOptionSelected(questionId, optionId))

        case (QuestionType.Select(options), AnswerValue.SingleChoice(optionId)) =>
          Op.failUnless(
            options.exists(_.id == optionId),
            Error.InvalidOptionSelected(questionId, optionId))

        case (QuestionType.Checkbox(options, rules), AnswerValue.MultipleChoice(optionIds)) =>
          val invalidIds = optionIds.filterNot(id => options.exists(_.id == id))
          for
            _ <- Op.failIf(
              invalidIds.nonEmpty,
              Error.InvalidOptionsSelected(questionId, invalidIds))
            _ <- rules.validate(questionId, optionIds.size)
          yield ()

        case (QuestionType.Input(rules), AnswerValue.Text(text)) =>
          rules.validate(questionId, text)

        case (QuestionType.Rating(rules), AnswerValue.Rating(v)) =>
          rules.validate(questionId, v)

        case (QuestionType.Numeric(rules), AnswerValue.Numeric(v)) =>
          rules.validate(questionId, v)

        case (QuestionType.Date(rules), AnswerValue.DateValue(d)) =>
          rules.validate(questionId, d)

        case _ =>
          Op.fail(Error.IncompatibleAnswerType(questionId, questionType))
  end extension

  extension [G[_]: Traverse](rules: G[CommonRule])
    def validate(questionId: Id, value: AnswerValue): RuleOp[Unit] =
      rules
        .parTraverse: rule =>
          rule match
            case CommonRule.Required =>
              val isEmpty =
                value match
                  case AnswerValue.Text(v) =>
                    v.trim.isEmpty
                  case AnswerValue.MultipleChoice(ids) =>
                    ids.isEmpty
                  case _ =>
                    false
              Op.failIf(isEmpty, Error.RequiredAnswerMissing(questionId))
        .void

  extension [G[_]: Traverse](rules: G[SelectionRule])
    def validate(questionId: Id, selectedCount: Int): RuleOp[Unit] =
      rules
        .parTraverse: rule =>
          rule match
            case SelectionRule.MinSelections(min) =>
              Op.failIf(selectedCount < min, Error.TooFewSelections(questionId, min, selectedCount))
            case SelectionRule.MaxSelections(max) =>
              Op.failIf(
                selectedCount > max,
                Error.TooManySelections(questionId, max, selectedCount))
        .void

  extension [G[_]: Traverse](rules: G[TextRule])
    def validate(questionId: Id, text: String): RuleOp[Unit] =
      rules
        .parTraverse: rule =>
          rule match
            case TextRule.MinLength(min) =>
              Op.failIf(text.length < min, Error.TextTooShort(questionId, min, text.length))
            case TextRule.MaxLength(max) =>
              Op.failIf(text.length > max, Error.TextTooLong(questionId, max, text.length))
            case TextRule.Pattern(regex) =>
              Op.failUnless(text.matches(regex), Error.InvalidPattern(questionId, regex))
            case TextRule.Email =>
              val emailRegex = "^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$"
              Op.failUnless(text.matches(emailRegex), Error.InvalidEmail(questionId))
            case TextRule.Url =>
              val urlRegex = "^https?://[\\w.-]+(?:/[\\w./-]*)?$"
              Op.failUnless(text.matches(urlRegex), Error.InvalidUrl(questionId))
        .void

  extension [G[_]: Traverse](rules: G[RangeRule[Int]])
    def validate(questionId: Id, value: Float): RuleOp[Unit] =
      rules
        .parTraverse: rule =>
          rule match
            case RangeRule.Min(min) =>
              Op.failIf(
                value < min,
                Error.RatingOutOfRange(questionId, min.toFloat, min.toFloat, value))
            case RangeRule.Max(max) =>
              Op.failIf(
                value > max,
                Error.RatingOutOfRange(questionId, max.toFloat, max.toFloat, value))
        .void

  extension [G[_]: Traverse](rules: G[RangeRule[BigDecimal]])
    def validate(questionId: Id, value: BigDecimal): RuleOp[Unit] =
      rules
        .parTraverse: rule =>
          rule match
            case RangeRule.Min(min) =>
              Op.failIf(value < min, Error.NumericOutOfRange(questionId, min, min, value))
            case RangeRule.Max(max) =>
              Op.failIf(value > max, Error.NumericOutOfRange(questionId, max, max, value))
        .void

  extension [G[_]: Traverse](rules: G[RangeRule[LocalDate]])
    def validate(questionId: Id, value: LocalDate): RuleOp[Unit] =
      rules
        .parTraverse: rule =>
          rule match
            case RangeRule.Min(min) =>
              Op.failIf(value.isBefore(min), Error.DateOutOfRange(questionId, min, min, value))
            case RangeRule.Max(max) =>
              Op.failIf(value.isAfter(max), Error.DateOutOfRange(questionId, max, max, value))
        .void
end ops
