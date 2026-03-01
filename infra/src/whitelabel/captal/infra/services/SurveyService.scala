package whitelabel.captal.infra.services

import io.getquill.*
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.{AnswerRow, QuestionRow, UserSurveyProgressRow}

object SurveyService:
  inline def findByUserAndSurveyQuery = quote: (userIdParam: user.Id, surveyIdParam: survey.Id) =>
    query[UserSurveyProgressRow].filter(progress =>
      progress.userId == userIdParam && progress.surveyId == surveyIdParam)

  inline def insertProgressQuery = quote:
    (
        id: String,
        userIdParam: user.Id,
        surveyIdParam: survey.Id,
        currentQuestionIdParam: Option[survey.question.Id],
        completedAt: Option[String],
        createdAt: String,
        updatedAt: String) =>
      query[UserSurveyProgressRow].insert(
        _.id                -> id,
        _.userId            -> userIdParam,
        _.surveyId          -> surveyIdParam,
        _.currentQuestionId -> currentQuestionIdParam,
        _.completedAt       -> completedAt,
        _.createdAt         -> createdAt,
        _.updatedAt         -> updatedAt
      )

  inline def updateTimestampQuery = quote: (progressId: String, updatedAt: String) =>
    query[UserSurveyProgressRow].filter(_.id == progressId).update(_.updatedAt -> updatedAt)

  // Check if survey is complete: all questions answered
  inline def isSurveyCompleteQuery = quote: (userIdParam: user.Id, surveyIdParam: survey.Id) =>
    query[QuestionRow].filter(_.surveyId == surveyIdParam).size <=
      query[AnswerRow]
        .filter(answer =>
          answer.userId == userIdParam &&
            query[QuestionRow]
              .filter(question =>
                question.surveyId == surveyIdParam && question.id == answer.questionId)
              .nonEmpty)
        .size

  inline def markCompleteQuery = quote:
    (progressId: String, completedAt: String, updatedAt: String) =>
      query[UserSurveyProgressRow]
        .filter(_.id == progressId)
        .update(_.completedAt -> Some(completedAt), _.updatedAt -> updatedAt)
end SurveyService
