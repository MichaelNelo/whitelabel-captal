package whitelabel.captal.core.survey.question

import java.time.LocalDate

enum AnswerValue:
  case SingleChoice(optionId: OptionId)
  case MultipleChoice(optionIds: Set[OptionId])
  case Text(value: String)
  case Rating(value: Float)
  case Numeric(value: BigDecimal)
  case DateValue(value: LocalDate)
