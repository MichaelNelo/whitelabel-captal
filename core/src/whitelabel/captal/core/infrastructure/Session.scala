package whitelabel.captal.core.infrastructure

import java.time.Instant

import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{application, user, video}

/** @param deviceId
  *   the device the session runs on, resolved from its fingerprint when the session was created.
  */
final case class SessionData(
    sessionId: user.SessionId,
    deviceId: user.DeviceId,
    userId: Option[user.Id],
    locale: String,
    phase: application.Phase,
    currentQuestion: Option[FullyQualifiedQuestionId],
    currentVideoId: Option[video.Id],
    currentAdvertiserId: Option[AdvertiserId] = None,
    locationId: Option[String] = None,
    clientMac: String = "",
    apMac: String = "",
    redirectUrl: String = "",
    ssid: String = "",
    fingerprint: String = "",
    accessExpiresAt: Option[Instant] = None)
