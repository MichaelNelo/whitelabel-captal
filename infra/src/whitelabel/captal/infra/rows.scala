package whitelabel.captal.infra

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.{survey, user, video}

final case class UserRow(
    id: user.Id,
    email: Option[user.Email],
    locale: String,
    createdAt: String,
    updatedAt: String)

final case class SessionRow(
    id: user.SessionId,
    userId: Option[user.Id],
    deviceId: user.DeviceId,
    locale: String,
    phase: Phase,
    currentSurveyId: Option[survey.Id],
    currentQuestionId: Option[survey.question.Id],
    currentVideoId: Option[video.Id],
    lastPromoVideoId: Option[video.Id],
    createdAt: String)

final case class SurveyRow(
    id: survey.Id,
    category: String,
    advertiserId: Option[String],
    isActive: Int,
    createdAt: String)

final case class QuestionRow(
    id: survey.question.Id,
    surveyId: survey.Id,
    questionType: String,
    pointsAwarded: Int,
    displayOrder: Int,
    hierarchyLevel: Option[String],
    isRequired: Int,
    createdAt: String)

final case class QuestionOptionRow(
    id: survey.question.OptionId,
    questionId: survey.question.Id,
    displayOrder: Int,
    parentOptionId: Option[survey.question.OptionId])

final case class QuestionRuleRow(
    id: String,
    questionId: survey.question.Id,
    ruleType: String,
    ruleConfig: String)

final case class AnswerRow(
    id: String,
    userId: user.Id,
    sessionId: user.SessionId,
    questionId: survey.question.Id,
    answerValue: String,
    answeredAt: String,
    createdAt: String)

final case class UserSurveyProgressRow(
    id: String,
    userId: user.Id,
    surveyId: survey.Id,
    currentQuestionId: Option[survey.question.Id],
    completedAt: Option[String],
    createdAt: String,
    updatedAt: String)

final case class NextIdentificationSurveyRow(
    surveyId: survey.Id,
    questionId: survey.question.Id,
    category: String)

final case class LocalizedTextRow(
    id: String,
    entityId: String,
    locale: String,
    value: String,
    category: String,
    createdAt: String,
    updatedAt: String)

final case class AdvertiserRow(
    id: String,
    name: String,
    priority: Int,
    isActive: Int,
    createdAt: String,
    updatedAt: String)

final case class AdvertiserVideoRow(
    id: video.Id,
    advertiserId: Option[String],
    videoType: String,
    videoUrl: String,
    durationSeconds: Int,
    minWatchSeconds: Int,
    showCountdown: Int,
    noRepeatSeconds: Option[Int],
    isActive: Int,
    priority: Int,
    createdAt: String,
    updatedAt: String)

final case class VideoViewRow(
    id: String,
    sessionId: user.SessionId,
    userId: Option[user.Id],
    videoId: video.Id,
    durationWatchedSeconds: Int,
    completed: Int,
    viewedAt: String,
    createdAt: String)

final case class DeviceRow(
    id: user.DeviceId,
    userAgent: String,
    createdAt: String,
    updatedAt: String)

final case class DeviceUserRow(
    deviceId: user.DeviceId,
    userId: user.Id,
    firstSeenAt: String,
    lastSeenAt: String)
