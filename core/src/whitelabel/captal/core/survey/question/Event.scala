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
  case AdvertiserQuestionAnswered(
      userId: user.Id,
      surveyId: survey.Id,
      advertiserId: AdvertiserId,
      questionId: Id,
      answer: QuestionAnswer,
      occurredAt: Instant)
end Event
