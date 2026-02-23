package whitelabel.captal.core.survey.question

enum CommonRule:
  case Required

enum SelectionRule:
  case MinSelections(min: Int)
  case MaxSelections(max: Int)

enum TextRule:
  case MinLength(min: Int)
  case MaxLength(max: Int)
  case Pattern(regex: String)
  case Email
  case Url

enum RangeRule[A]:
  case Min(value: A)
  case Max(value: A)
