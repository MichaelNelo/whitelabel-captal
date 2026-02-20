package whitelabel.captal.core.survey.question

import java.time.LocalDate

/** Supported question types with their configuration */
enum QuestionType:
  case Radio(options: List[QuestionOption])
  case Checkbox(options: List[QuestionOption], rules: List[SelectionRule])
  case Select(options: List[QuestionOption])
  case Input(rules: List[TextRule])
  case Rating(rules: List[RangeRule[Int]])
  case Numeric(rules: List[RangeRule[BigDecimal]])
  case Date(rules: List[RangeRule[LocalDate]])
