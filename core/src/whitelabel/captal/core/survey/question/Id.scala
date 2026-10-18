package whitelabel.captal.core.survey.question

import java.util.UUID

import zio.schema.Schema

opaque type Id = UUID
object Id:
  def apply(value: UUID): Id = value
  def generate: Id = UUID.randomUUID()
  def fromString(s: String): Option[Id] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): Id = UUID.fromString(s)

  extension (id: Id)
    def value: UUID = id
    def asString: String = id.toString

  // See the note on `survey.Id`: an opaque type is invisible to `DeriveSchema.gen`.
  given Schema[Id] = Schema[String].transformOrFail(
    text => fromString(text).toRight(s"Not a question id: $text"),
    id => Right(id.asString))

opaque type OptionId = UUID
object OptionId:
  def apply(value: UUID): OptionId = value
  def generate: OptionId = UUID.randomUUID()
  def fromString(s: String): Option[OptionId] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): OptionId = UUID.fromString(s)

  extension (id: OptionId)
    def value: UUID = id
    def asString: String = id.toString

  given Schema[OptionId] = Schema[String].transformOrFail(
    text => fromString(text).toRight(s"Not an option id: $text"),
    id => Right(id.asString))
