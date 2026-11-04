package whitelabel.captal.platform

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.device.Mac
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
    currentAdvertiserId: Option[String],
    locationId: Option[String],
    createdAt: String,
    clientMac: Option[Mac],
    apMac: Option[Mac],
    redirectUrl: String,
    ssid: String,
    fingerprint: String,
    accessExpiresAt: Option[String] = None)

/** Which survey this is, as stored in `surveys.category` and constrained by V1's CHECK. `inline` so
  * a quoted query can name them — a plain `val` cannot be, and the object-level quotes the read
  * path uses have no `lift` available.
  *
  * [[Advertiser]] is a misnomer: it is the survey that follows a *video*, whoever the video belongs
  * to, and promos may carry one too.
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
    createdAt: String,
    deviceId: Option[user.DeviceId] = None)

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

/** Where a video is in its life, as one value rather than two flags. Introduced by V21, whose CHECK
  * admits exactly these three. `inline` so a quoted query can name them: a plain `val` is opaque to
  * Quill's parser, while an `inline val` of a literal is constant-folded before Quill sees it.
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
    /** The enum rather than its stored word: Quill maps it through the `MappedEncoding` in
      * `schema.core`, so a query reads `v.videoType == lift(VideoType.Promo)`.
      */
    videoType: video.VideoType,
    minWatchSeconds: Int,
    showCountdown: Int,
    noRepeatSeconds: Option[Int],
    locationId: Option[String],
    status: String,
    priority: Int,
    createdAt: String,
    updatedAt: String,
    productCampaignId: Option[String] = None)

/** One uploaded file of a video. The video plays the newest confirmed one; `deletedAt` is set once
  * its object is gone from the bucket.
  */
final case class VideoFileRow(
    id: String,
    videoId: video.Id,
    url: String,
    durationSeconds: Option[Int],
    createdAt: String,
    confirmedAt: Option[String],
    deletedAt: Option[String])

/** An error a guest could be offered a way out of. `origin` is `server` or `client`; times are
  * fixed-width stamps, compared as text.
  */
final case class ErrorRow(
    id: String,
    sessionId: Option[user.SessionId],
    deviceId: Option[user.DeviceId],
    origin: String,
    message: String,
    detail: String,
    fingerprint: Option[String],
    verdict: Option[String],
    createdAt: String,
    availableAt: Option[String],
    redeemedAt: Option[String])

/** One guest playing one file; open while `endedAt` is empty. `expired` is 1 when the backoffice
  * closed it because its page never did.
  */
final case class VideoPlaybackRow(
    id: String,
    sessionId: user.SessionId,
    fileId: String,
    startedAt: String,
    endedAt: Option[String],
    expired: Int = 0)

final case class VideoViewRow(
    id: String,
    sessionId: user.SessionId,
    userId: Option[user.Id],
    videoId: video.Id,
    durationWatchedSeconds: Int,
    completed: Int,
    viewedAt: String,
    createdAt: String,
    deviceId: Option[user.DeviceId] = None)

/** A device, found again by the fingerprint UniFi's redirect carries. `userAgent` is the latest one
  * seen, for diagnosis: it does not identify anything.
  */
final case class DeviceRow(
    id: user.DeviceId,
    userAgent: String,
    createdAt: String,
    updatedAt: String,
    clientMac: Option[Mac],
    fingerprint: String,
    termsId: Option[String] = None,
    termsAcceptedAt: Option[String] = None)

/** A version of the terms of service, the same for every location. */
final case class TermsRow(id: String, version: Int, createdAt: String)

/** A version's text in one locale. */
final case class TermsTranslationRow(
    termsId: String,
    locale: String,
    body: String,
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
    unifiRedirectUrl: Option[String] = None,
    unifiIsEnabled: Int = 1)

/** What a location changed of the recovery defaults; `None` takes the default. See
  * [[LocationRecovery]].
  */
final case class LocationRecoveryRow(
    locationId: String,
    maximumSeconds: Option[Int] = None,
    ceilingSeconds: Option[Int] = None,
    zeroBelowSeconds: Option[Int] = None,
    videoWeight: Option[Double] = None,
    videoAnswerWeight: Option[Double] = None,
    identificationWeight: Option[Double] = None,
    emailWeight: Option[Double] = None,
    identifiedFactor: Option[Double] = None,
    watchedFactor: Option[Double] = None,
    recoveryExponent: Option[Double] = None,
    fabricatedFactor: Option[Double] = None,
    recurrentFactor: Option[Double] = None,
    recoveryWindowSeconds: Option[Int] = None,
    recurrenceWindowSeconds: Option[Int] = None,
    playbackGraceSeconds: Option[Int] = None,
    updatedAt: String)

final case class EventLogRow(
    id: String,
    eventType: String,
    eventData: String,
    sessionId: String,
    userId: Option[String],
    occurredAt: String,
    createdAt: String)
