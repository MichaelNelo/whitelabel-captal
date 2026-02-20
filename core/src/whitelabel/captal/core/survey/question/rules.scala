package whitelabel.captal.core.survey.question

/** Common rules applicable to any question type */
enum CommonRule:
  case Required

/** Rules for Checkbox questions */
enum SelectionRule:
  case MinSelections(min: Int)
  case MaxSelections(max: Int)

/** Rules for Input questions */
enum TextRule:
  case MinLength(min: Int)
  case MaxLength(max: Int)
  case Pattern(regex: String)
  case Email
  case Url

/** Rules for Rating, Numeric, and Date questions */
enum RangeRule[A]:
  case Min(value: A)
  case Max(value: A)
