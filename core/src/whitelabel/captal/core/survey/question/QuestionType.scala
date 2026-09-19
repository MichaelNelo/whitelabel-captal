package whitelabel.captal.core.survey.question

import java.time.LocalDate

import com.github.plokhotnyuk.jsoniter_scala.macros.named

enum QuestionType:
  case Radio(options: List[QuestionOption])
  case Checkbox(options: List[QuestionOption], rules: List[SelectionRule])

  /** The wire has called this `"dropdown"` since before the type was named `Select`, and the
    * annotation is what keeps the two from having to agree.
    */
  @named("dropdown")
  case Select(options: List[QuestionOption])
  case Input(rules: List[TextRule])
  case Rating(rules: List[RangeRule[Int]])
  case Numeric(rules: List[RangeRule[BigDecimal]])
  case Date(rules: List[RangeRule[LocalDate]])
