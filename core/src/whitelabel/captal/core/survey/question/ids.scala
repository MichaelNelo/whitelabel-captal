package whitelabel.captal.core.survey.question

import java.util.UUID

opaque type QuestionId = UUID
object QuestionId:
  def apply(value: UUID): QuestionId            = value
  def generate: QuestionId                      = UUID.randomUUID()
  def fromString(s: String): Option[QuestionId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: QuestionId)
    def value: UUID      = id
    def asString: String = id.toString

opaque type OptionId = UUID
object OptionId:
  def apply(value: UUID): OptionId            = value
  def generate: OptionId                      = UUID.randomUUID()
  def fromString(s: String): Option[OptionId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: OptionId)
    def value: UUID      = id
    def asString: String = id.toString
