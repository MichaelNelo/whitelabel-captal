package whitelabel.captal.core.survey

import java.time.LocalDate

import whitelabel.captal.core.survey.question.{HierarchyLevel, OptionId, QuestionId, QuestionType}

enum Error:
  // Common rule errors
  case RequiredAnswerMissing(questionId: QuestionId)

  // Selection rule errors
  case InvalidOptionSelected(questionId: QuestionId, optionId: OptionId)
  case InvalidOptionsSelected(questionId: QuestionId, optionIds: Set[OptionId])
  case TooFewSelections(questionId: QuestionId, min: Int, actual: Int)
  case TooManySelections(questionId: QuestionId, max: Int, actual: Int)

  // Text rule errors
  case TextTooShort(questionId: QuestionId, minLength: Int, actual: Int)
  case TextTooLong(questionId: QuestionId, maxLength: Int, actual: Int)
  case InvalidPattern(questionId: QuestionId, pattern: String)
  case InvalidEmail(questionId: QuestionId)
  case InvalidUrl(questionId: QuestionId)

  // Range rule errors
  case RatingOutOfRange(questionId: QuestionId, min: Float, max: Float, actual: Float)
  case NumericOutOfRange(
      questionId: QuestionId,
      min: BigDecimal,
      max: BigDecimal,
      actual: BigDecimal)
  case DateOutOfRange(questionId: QuestionId, min: LocalDate, max: LocalDate, actual: LocalDate)

  // Type mismatch
  case IncompatibleAnswerType(questionId: QuestionId, expected: QuestionType)

  // State errors
  case QuestionAlreadyAnswered(questionId: QuestionId)
  case QuestionNotFound(questionId: QuestionId)
  case HierarchyViolation(questionId: QuestionId, expectedLevel: HierarchyLevel)
