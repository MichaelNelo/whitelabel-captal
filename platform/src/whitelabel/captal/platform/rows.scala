package whitelabel.captal.platform

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
    currentAdvertiserId: Option[String],
    locationId: Option[String],
    createdAt: String,
    clientMac: String,
    apMac: String,
    redirectUrl: String,
    ssid: String,
    clickId: String,
    accessExpiresAt: Option[String] = None)

/** Which survey this is, as stored in `surveys.category` and constrained by V1's CHECK.
  *
  * `inline`, for the reason [[VideoStatus]] records: a plain `val` cannot be named inside a quoted
  * query, and the object-level quotes the read path uses have no `lift` available. Six comparisons
  * across `SurveyRepositoryQuill` were spelling these out by hand.
  *
  * [[Advertiser]] is a misnomer since promos may carry a survey too — it is the survey that follows
  * a *video*, whoever the video belongs to. Renaming the stored value would mean another table
  * rebuild plus the `api/survey/advertiser` routes and the client, so the name stays and this note
  * stands in for it.
  */
object SurveyCategory:
  /** Asked of every guest before anything else, to get a user to hang answers off. */
  inline val Email = "email"

  /** Deployment-wide, asked once the guest has an email. */
  inline val Profiling = "profiling"

  /** Deployment-wide, asked after profiling is exhausted. */
  inline val Location = "location"

  /** Follows one video, identified by `survey.video_id`. */
  inline val Advertiser = "advertiser"

final case class SurveyRow(
    id: survey.Id,
    category: String,
    advertiserId: Option[String],
    videoId: Option[String],
    locationId: Option[String],
    isActive: Int,
    createdAt: String,
    name: Option[String] = None)

final case class QuestionRow(
    id: survey.question.Id,
    surveyId: survey.Id,
    questionType: String,
    pointsAwarded: Int,
    displayOrder: Int,
    hierarchyLevel: Option[String],
    isRequired: Int,
    createdAt: String,
    isActive: Int = 1)

final case class QuestionOptionRow(
    id: survey.question.OptionId,
    questionId: survey.question.Id,
    displayOrder: Int,
    parentOptionId: Option[survey.question.OptionId],
    isActive: Int = 1)

final case class QuestionRuleRow(
    id: String,
    questionId: survey.question.Id,
    ruleType: String,
    ruleConfig: String,
    isActive: Int = 1)

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
    updatedAt: String,
    locationId: Option[String] = None,
    isActive: Int = 1)

final case class AdvertiserRow(
    id: String,
    name: String,
    priority: Int,
    isActive: Int,
    createdAt: String,
    updatedAt: String)

/** Where a video is in its life, as one value rather than two flags.
  *
  * `pending` and `retired` were both `is_active = 0` until V21, which let an upload path put a
  * deliberately retired video back into rotation. The fourth combination two booleans would allow —
  * active with no content — is not representable here, and the column's CHECK enforces it.
  *
  * `inline` so a quoted query can name them. A plain `val` is opaque to Quill's parser, and `lift`
  * is not available inside the object-level quotes the read path uses — so the only way to filter
  * on a status was to write `"active"` out again, which is exactly what this object exists to stop.
  * An `inline val` of a literal is constant-folded before Quill ever sees it.
  */
object VideoStatus:
  /** Created, with a key reserved and a row to hang its survey off, but no file in S3 yet. Served
    * to nobody.
    */
  inline val Pending = "pending"

  /** In rotation. */
  inline val Active = "active"

  /** Taken out of rotation deliberately. Keeps its file; the answers to its survey still resolve.
    */
  inline val Retired = "retired"

final case class AdvertiserVideoRow(
    id: video.Id,
    advertiserId: Option[String],
    /** The enum rather than its stored word. Quill maps it through the `MappedEncoding` in
      * `schema.core`, so a query reads `v.videoType == lift(VideoType.Promo)` and the compiler
      * refuses anything that is not one of the two — where a `String` let `"propaganda"` be typed
      * into three separate queries, any of which could have been wrong without a word from anyone.
      */
    videoType: video.VideoType,
    videoUrl: String,
    durationSeconds: Int,
    minWatchSeconds: Int,
    showCountdown: Int,
    noRepeatSeconds: Option[Int],
    locationId: Option[String],
    status: String,
    priority: Int,
    createdAt: String,
    updatedAt: String,
    productCampaignId: Option[String] = None)

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

final case class LocationRow(
    id: String,
    slug: String,
    name: String,
    isActive: Int,
    createdAt: String,
    updatedAt: String,
    unifiApMac: Option[String],
    unifiHost: Option[String] = None,
    unifiPort: Option[Int] = None,
    unifiSiteId: Option[String] = None,
    unifiApiToken: Option[String] = None,
    unifiDurationMinutes: Option[Int] = None,
    unifiRedirectUrl: Option[String] = None)

final case class EventLogRow(
    id: String,
    eventType: String,
    eventData: String,
    sessionId: String,
    userId: Option[String],
    occurredAt: String,
    createdAt: String)
