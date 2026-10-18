package whitelabel.captal.platform.services

import io.getquill.*
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.platform.{AnswerRow, QuestionRow, UserSurveyProgressRow}

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

  /** True when every *active* question of the survey has an answer from this user.
    *
    * `==` rather than `<=` is safe because of `idx_answers_user_question`, the unique index on
    * `answers(user_id, question_id)` from V1: every counted answer maps to a distinct active
    * question of this survey, so the right side can never exceed the left.
    *
    * Both sides filter on is_active, and both are needed. With only the left side filtered,
    * deactivating an already-answered question would close the survey by itself: the retired
    * question leaves the left count, but its answer stays on the right and papers over the gap it
    * just created, marking the survey complete with other questions still unanswered.
    */
  inline def isSurveyCompleteQuery = quote: (userIdParam: user.Id, surveyIdParam: survey.Id) =>
    query[QuestionRow].filter(q => q.surveyId == surveyIdParam && q.isActive == 1).size ==
      query[AnswerRow]
        .filter(answer =>
          answer.userId == userIdParam &&
            query[QuestionRow]
              .filter(question =>
                question.surveyId == surveyIdParam && question.id == answer.questionId &&
                  question.isActive == 1)
              .nonEmpty)
        .size

  inline def markCompleteQuery = quote:
    (progressId: String, completedAt: String, updatedAt: String) =>
      query[UserSurveyProgressRow]
        .filter(_.id == progressId)
        .update(_.completedAt -> Some(completedAt), _.updatedAt -> updatedAt)
end SurveyService
