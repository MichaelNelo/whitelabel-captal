package whitelabel.captal.core.survey

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

  /** The string the JSON carries, for zio-http's `Endpoint` API — where a `Schema` is the codec
    * rather than documentation, so every type a response reaches needs a real one.
    *
    * Beside the declaration because `DeriveSchema.gen` cannot see through an opaque type: it
    * reports "Deriving schema for survey.Id is not supported", and this is the only place that can
    * say what the string is. A transform rather than a primitive so a malformed id in a request
    * body decodes to an error instead of throwing.
    */
  given Schema[Id] = Schema[String].transformOrFail(
    text => fromString(text).toRight(s"Not a survey id: $text"),
    id => Right(id.asString))
end Id

opaque type AdvertiserId = UUID
object AdvertiserId:
  def apply(value: UUID): AdvertiserId = value
  def generate: AdvertiserId = UUID.randomUUID()
  def fromString(s: String): Option[AdvertiserId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: AdvertiserId)
    def value: UUID = id
    def asString: String = id.toString
