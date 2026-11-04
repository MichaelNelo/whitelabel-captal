package whitelabel.captal.core.application

import io.circe.{Decoder, Encoder}

enum Phase:
  case Welcome

  /** The welcome, for a device that has to accept the terms of service before it starts: it never
    * accepted them, accepted a version that is no longer the current one, or came with neither the
    * session nor the user cookie. Only accepting them leaves it.
    */
  case WelcomeNeedsToS
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserVideoSurvey
  case AdvertiserQuestion
  case Ready

  /** Left through an error: what `finish` grants access from, as from `Ready`, but with no user
    * required — the error may have come before the guest gave an email.
    */
  case Recovered
  case Authorized

object Phase:
  def toDbString(phase: Phase): String =
    phase match
      case Welcome =>
        "welcome"
      case WelcomeNeedsToS =>
        "welcome_needs_tos"
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
      case Recovered =>
        "recovered"
      case Authorized =>
        "authorized"

  def fromDbString(s: String): Phase =
    s match
      case "welcome" =>
        Welcome
      case "welcome_needs_tos" =>
        WelcomeNeedsToS
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
      case "recovered" =>
        Recovered
      case "authorized" =>
        Authorized
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
