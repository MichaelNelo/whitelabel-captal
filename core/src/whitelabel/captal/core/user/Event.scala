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

  /** `advertiserId` is optional, matching [[VideoAssigned]] directly above — the video it follows
    * may be a promo, which belongs to the venue rather than to an advertiser.
    */
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

  /** The access granted after `UserFinishedProcess` has reached its `accessExpiresAt`.
    *
    * A genuine domain event: it is what forces the user back through the flow, which is the
    * business model. Emitted by the expiration scheduler, not by a request.
    */
  case AccessExpired(userId: Id, occurredAt: Instant)
end Event
