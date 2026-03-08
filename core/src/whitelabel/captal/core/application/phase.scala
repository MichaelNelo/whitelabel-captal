package whitelabel.captal.core.application

import io.circe.{Decoder, Encoder}

enum Phase:
  case Welcome
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserVideoSurvey
  case AdvertiserQuestion
  case Ready

object Phase:
  def toDbString(phase: Phase): String =
    phase match
      case Welcome =>
        "welcome"
      case IdentificationQuestion =>
        "identification_question"
      case AdvertiserVideo =>
        "advertiser_video"
      case AdvertiserVideoSurvey =>
        "advertiser_video_survey"
      case AdvertiserQuestion =>
        "advertiser_question"
      case Ready =>
        "ready"

  def fromDbString(s: String): Phase =
    s match
      case "welcome" =>
        Welcome
      case "identification_question" =>
        IdentificationQuestion
      case "advertiser_video" =>
        AdvertiserVideo
      case "advertiser_video_survey" =>
        AdvertiserVideoSurvey
      case "advertiser_question" =>
        AdvertiserQuestion
      case "ready" =>
        Ready
      case _ =>
        Welcome

  given Encoder[Phase] = Encoder.encodeString.contramap(toDbString)
  given Decoder[Phase] = Decoder.decodeString.map(fromDbString)
end Phase

enum IdentificationSurveyType:
  case Email,
    Profiling,
    Location

object IdentificationSurveyType:
  def toDbString(surveyType: IdentificationSurveyType): String =
    surveyType match
      case Email =>
        "email"
      case Profiling =>
        "profiling"
      case Location =>
        "location"

  def fromDbString(s: String): IdentificationSurveyType =
    s match
      case "email" =>
        Email
      case "profiling" =>
        Profiling
      case "location" =>
        Location
      case _ =>
        Email

  given Encoder[IdentificationSurveyType] = Encoder.encodeString.contramap(toDbString)
  given Decoder[IdentificationSurveyType] = Decoder.decodeString.map(fromDbString)
end IdentificationSurveyType
