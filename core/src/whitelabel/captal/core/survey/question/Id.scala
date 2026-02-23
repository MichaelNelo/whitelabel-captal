package whitelabel.captal.core.survey.question

import java.util.UUID

opaque type Id = UUID
object Id:
  def apply(value: UUID): Id = value
  def generate: Id = UUID.randomUUID()
  def fromString(s: String): Option[Id] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): Id = UUID.fromString(s)

  extension (id: Id)
    def value: UUID = id
    def asString: String = id.toString

opaque type OptionId = UUID
object OptionId:
  def apply(value: UUID): OptionId = value
  def generate: OptionId = UUID.randomUUID()
  def fromString(s: String): Option[OptionId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: OptionId)
    def value: UUID = id
    def asString: String = id.toString
