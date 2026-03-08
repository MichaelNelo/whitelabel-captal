package whitelabel.captal.infra.schema

import scala.annotation.targetName

import io.getquill.*
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.{survey, user, video}

package object core:
  // Mapped Encodings - User types
  @targetName("userIdToString")
  inline given MappedEncoding[user.Id, String] = MappedEncoding(_.asString)
  @targetName("stringToUserId")
  inline given MappedEncoding[String, user.Id] = MappedEncoding(user.Id.unsafe)

  @targetName("sessionIdToString")
  inline given MappedEncoding[user.SessionId, String] = MappedEncoding(_.asString)
  @targetName("stringToSessionId")
  inline given MappedEncoding[String, user.SessionId] = MappedEncoding(user.SessionId.unsafe)

  @targetName("deviceIdToString")
  inline given MappedEncoding[user.DeviceId, String] = MappedEncoding(_.asString)
  @targetName("stringToDeviceId")
  inline given MappedEncoding[String, user.DeviceId] = MappedEncoding(user.DeviceId.unsafe)

  @targetName("emailToString")
  inline given MappedEncoding[user.Email, String] = MappedEncoding(_.value)
  @targetName("stringToEmail")
  inline given MappedEncoding[String, user.Email] = MappedEncoding(user.Email.unsafeFrom)

  // Mapped Encodings - Survey types
  @targetName("surveyIdToString")
  inline given MappedEncoding[survey.Id, String] = MappedEncoding(_.asString)
  @targetName("stringToSurveyId")
  inline given MappedEncoding[String, survey.Id] = MappedEncoding(survey.Id.unsafe)

  @targetName("questionIdToString")
  inline given MappedEncoding[survey.question.Id, String] = MappedEncoding(_.asString)
  @targetName("stringToQuestionId")
  inline given MappedEncoding[String, survey.question.Id] = MappedEncoding(
    survey.question.Id.unsafe)

  @targetName("optionIdToString")
  inline given MappedEncoding[survey.question.OptionId, String] = MappedEncoding(_.asString)
  @targetName("stringToOptionId")
  inline given MappedEncoding[String, survey.question.OptionId] = MappedEncoding(
    survey.question.OptionId.unsafe)

  // Mapped Encodings - Phase
  @targetName("phaseToString")
  inline given MappedEncoding[Phase, String] = MappedEncoding(Phase.toDbString)
  @targetName("stringToPhase")
  inline given MappedEncoding[String, Phase] = MappedEncoding(Phase.fromDbString)

  // Mapped Encodings - IdentificationSurveyType
  @targetName("identificationSurveyTypeToString")
  inline given MappedEncoding[IdentificationSurveyType, String] = MappedEncoding(
    IdentificationSurveyType.toDbString)
  @targetName("stringToIdentificationSurveyType")
  inline given MappedEncoding[String, IdentificationSurveyType] = MappedEncoding(
    IdentificationSurveyType.fromDbString)

  // Mapped Encodings - Video types
  @targetName("videoIdToString")
  inline given MappedEncoding[video.Id, String] = MappedEncoding(_.asString)
  @targetName("stringToVideoId")
  inline given MappedEncoding[String, video.Id] = MappedEncoding(video.Id.unsafe)

  @targetName("videoTypeToString")
  inline given MappedEncoding[video.VideoType, String] = MappedEncoding(video.VideoType.toDbString)
  @targetName("stringToVideoType")
  inline given MappedEncoding[String, video.VideoType] = MappedEncoding(video.VideoType.fromDbString)
end core
