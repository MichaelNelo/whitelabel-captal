package whitelabel.captal.core.survey

import java.util.UUID

opaque type SurveyId = UUID
object SurveyId:
  def apply(value: UUID): SurveyId            = value
  def generate: SurveyId                      = UUID.randomUUID()
  def fromString(s: String): Option[SurveyId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: SurveyId)
    def value: UUID      = id
    def asString: String = id.toString

opaque type AdvertiserId = UUID
object AdvertiserId:
  def apply(value: UUID): AdvertiserId            = value
  def generate: AdvertiserId                      = UUID.randomUUID()
  def fromString(s: String): Option[AdvertiserId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: AdvertiserId)
    def value: UUID      = id
    def asString: String = id.toString
