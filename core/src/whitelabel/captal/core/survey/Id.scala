package whitelabel.captal.core.survey

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

opaque type AdvertiserId = UUID
object AdvertiserId:
  def apply(value: UUID): AdvertiserId = value
  def generate: AdvertiserId = UUID.randomUUID()
  def fromString(s: String): Option[AdvertiserId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: AdvertiserId)
    def value: UUID = id
    def asString: String = id.toString
