package whitelabel.captal.core.survey.question

import java.time.LocalDate

import com.github.plokhotnyuk.jsoniter_scala.macros.named

/** The answer a guest gave, in the shape the wire has always used: a `"type"` tag and the payload
  * under `"value"`, whatever the case calls its own field.
  *
  * The annotations carry that mapping rather than a hand-written codec, and they are on the cases
  * because that is where the exceptions are — three tags do not follow their case name, and two
  * fields do not follow the key.
  */
enum AnswerValue:
  @named("single")
  case SingleChoice(
      @named("value")
      optionId: OptionId)
  @named("multiple")
  case MultipleChoice(
      @named("value")
      optionIds: Set[OptionId])
  case Text(value: String)
  case Rating(value: Float)
  case Numeric(value: BigDecimal)
  @named("date")
  case DateValue(value: LocalDate)
