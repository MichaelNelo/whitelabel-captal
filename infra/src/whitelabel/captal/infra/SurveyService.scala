package whitelabel.captal.infra

import io.getquill.*

object SurveyService:
  inline def findByUserAndSurveyQuery = quote: (visitorId: String, surveyId: String) =>
    query[UserSurveyProgressRow].filter(p => p.userId == visitorId && p.surveyId == surveyId)

  inline def insertProgressQuery = quote:
    (
        id: String,
        visitorId: String,
        surveyId: String,
        currentQuestionId: Option[String],
        completedAt: Option[String],
        createdAt: String,
        updatedAt: String) =>
      query[UserSurveyProgressRow].insert(
        _.id                -> id,
        _.userId            -> visitorId,
        _.surveyId          -> surveyId,
        _.currentQuestionId -> currentQuestionId,
        _.completedAt       -> completedAt,
        _.createdAt         -> createdAt,
        _.updatedAt         -> updatedAt
      )

  inline def updateTimestampQuery = quote: (progressId: String, updatedAt: String) =>
    query[UserSurveyProgressRow].filter(_.id == progressId).update(_.updatedAt -> updatedAt)

  // Check if survey is complete: all questions answered
  inline def isSurveyCompleteQuery = quote: (visitorId: String, surveyId: String) =>
    query[QuestionRow].filter(_.surveyId == surveyId).size <=
      query[AnswerRow]
        .filter(a =>
          a.userId == visitorId &&
            query[QuestionRow].filter(q => q.surveyId == surveyId && q.id == a.questionId).nonEmpty)
        .size

  inline def markCompleteQuery = quote:
    (progressId: String, completedAt: String, updatedAt: String) =>
      query[UserSurveyProgressRow]
        .filter(_.id == progressId)
        .update(_.completedAt -> Some(completedAt), _.updatedAt -> updatedAt)
end SurveyService
