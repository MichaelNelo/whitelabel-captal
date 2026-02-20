package whitelabel.captal.core.survey.question

import java.time.Instant

import whitelabel.captal.core.survey.{AdvertiserId, SurveyId}
import whitelabel.captal.core.user.{SessionId, UserId}

/** User-facing events for answering questions */
enum Event:
  case EmailQuestionAnswered(
      userId: UserId,
      sessionId: SessionId,
      surveyId: SurveyId,
      questionId: QuestionId,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
  case ProfilingQuestionAnswered(
      userId: UserId,
      sessionId: SessionId,
      surveyId: SurveyId,
      questionId: QuestionId,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
  case LocationQuestionAnswered(
      userId: UserId,
      sessionId: SessionId,
      surveyId: SurveyId,
      questionId: QuestionId,
      hierarchyLevel: HierarchyLevel,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
  case AdvertiserQuestionAnswered(
      userId: UserId,
      sessionId: SessionId,
      surveyId: SurveyId,
      advertiserId: AdvertiserId,
      questionId: QuestionId,
      answer: QuestionAnswer,
      locale: String,
      occurredAt: Instant)
