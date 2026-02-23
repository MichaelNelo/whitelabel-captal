package whitelabel.captal.core.survey.question

import java.time.Instant

import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.user.SessionId
import whitelabel.captal.core.{survey, user}

enum Event:
  case EmailQuestionAnswered(
      userId: user.Id,
      sessionId: SessionId,
      surveyId: survey.Id,
      questionId: Id,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
  case ProfilingQuestionAnswered(
      userId: user.Id,
      sessionId: SessionId,
      surveyId: survey.Id,
      questionId: Id,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
  case LocationQuestionAnswered(
      userId: user.Id,
      sessionId: SessionId,
      surveyId: survey.Id,
      questionId: Id,
      hierarchyLevel: HierarchyLevel,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
  case AdvertiserQuestionAnswered(
      userId: user.Id,
      sessionId: SessionId,
      surveyId: survey.Id,
      advertiserId: AdvertiserId,
      questionId: Id,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
end Event
