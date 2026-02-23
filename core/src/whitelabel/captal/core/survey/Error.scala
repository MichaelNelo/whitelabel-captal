package whitelabel.captal.core.survey

import java.time.LocalDate

import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.{HierarchyLevel, OptionId, QuestionType}

enum Error:
  // Common rule errors
  case RequiredAnswerMissing(questionId: survey.question.Id)

  // Selection rule errors
  case InvalidOptionSelected(questionId: survey.question.Id, optionId: OptionId)
  case InvalidOptionsSelected(questionId: survey.question.Id, optionIds: Set[OptionId])
  case TooFewSelections(questionId: survey.question.Id, min: Int, actual: Int)
  case TooManySelections(questionId: survey.question.Id, max: Int, actual: Int)

  // Text rule errors
  case TextTooShort(questionId: survey.question.Id, minLength: Int, actual: Int)
  case TextTooLong(questionId: survey.question.Id, maxLength: Int, actual: Int)
  case InvalidPattern(questionId: survey.question.Id, pattern: String)
  case InvalidEmail(questionId: survey.question.Id)
  case InvalidUrl(questionId: survey.question.Id)

  // Range rule errors
  case RatingOutOfRange(questionId: survey.question.Id, min: Float, max: Float, actual: Float)
  case NumericOutOfRange(
      questionId: survey.question.Id,
      min: BigDecimal,
      max: BigDecimal,
      actual: BigDecimal)
  case DateOutOfRange(
      questionId: survey.question.Id,
      min: LocalDate,
      max: LocalDate,
      actual: LocalDate)

  // Type mismatch
  case IncompatibleAnswerType(questionId: survey.question.Id, expected: QuestionType)

  // State errors
  case QuestionAlreadyAnswered(questionId: survey.question.Id)
  case QuestionNotFound(questionId: survey.question.Id)
  case HierarchyViolation(questionId: survey.question.Id, expectedLevel: HierarchyLevel)
end Error
