package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.video

enum Event:
  case UserCreated(userId: Id, email: Email, occurredAt: Instant)
  case SurveyAssigned(userId: Id, nextQuestion: FullyQualifiedQuestionId, occurredAt: Instant)
  case NewUserArrived(
      userId: Id,
      nextQuestion: Option[FullyQualifiedQuestionId],
      occurredAt: Instant)
  case IdentificationCompleted(userId: Id, occurredAt: Instant)
  case VideoAssigned(
      userId: Id,
      videoId: video.Id,
      advertiserId: Option[AdvertiserId],
      videoType: video.VideoType,
      occurredAt: Instant)

  /** `advertiserId` is `None` when the video it follows is a promo. */
  case VideoSurveyAssigned(
      userId: Id,
      advertiserId: Option[AdvertiserId],
      nextQuestion: FullyQualifiedQuestionId,
      occurredAt: Instant)
  case UserFinishedProcess(
      userId: Id,
      videoId: Option[video.Id],
      answeredQuestionIds: List[FullyQualifiedQuestionId],
      occurredAt: Instant)

  /** The access granted by `UserFinishedProcess` reached its `accessExpiresAt`. Emitted by the
    * expiration scheduler, not by a request.
    */
  case AccessExpired(userId: Id, occurredAt: Instant)
end Event
