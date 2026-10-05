package whitelabel.captal.core.survey.question

import java.time.Instant

import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.{survey, user}

enum Event:
  case EmailQuestionAnswered(
      userId: user.Id,
      surveyId: survey.Id,
      questionId: Id,
      answer: QuestionAnswer,
      occurredAt: Instant)
  case ProfilingQuestionAnswered(
      userId: user.Id,
      surveyId: survey.Id,
      questionId: Id,
      answer: QuestionAnswer,
      occurredAt: Instant)
  case LocationQuestionAnswered(
      userId: user.Id,
      surveyId: survey.Id,
      questionId: Id,
      hierarchyLevel: HierarchyLevel,
      answer: QuestionAnswer,
      occurredAt: Instant)

  /** An answer to a question that followed a video. `advertiserId` is absent when that video was a
    * promo — the venue's own content, which has no advertiser to attribute the answer to.
    */
  case AdvertiserQuestionAnswered(
      userId: user.Id,
      surveyId: survey.Id,
      advertiserId: Option[AdvertiserId],
      questionId: Id,
      answer: QuestionAnswer,
      occurredAt: Instant)
end Event
