package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.survey.question.{AnswerValue, QuestionAnswer}
import whitelabel.captal.core.survey.{
  Error as SurveyError,
  Event as SurveyEvent,
  State as SurveyState,
  Survey,
  ops as surveyOps
}
import whitelabel.captal.core.{Op, survey}

object ops:
  type UserOp[A] = Op[Event, Error, A]

  def createWithEmail(email: Email, now: Instant): UserOp[User[State.WithEmail]] =
    val userId = Id.generate
    val user = User[State.WithEmail](userId, State.WithEmail(email))
    val event = Event.UserCreated(userId, email, now)
    Op.emit(event, user)

  extension (user: User[State.WithEmail])
    def assignSurvey(
        surveyId: survey.Id,
        questionId: survey.question.Id,
        now: Instant): UserOp[User[State.AnsweringQuestion]] =
      val event = Event.SurveyAssigned(user.id, surveyId, questionId, now)
      val newUser = user.copy[State.AnsweringQuestion](state = State.AnsweringQuestion(
        surveyId,
        questionId))
      Op.emit(event, newUser)

    def answerEmail(
        survey: Survey[SurveyState.WithEmailQuestion],
        value: AnswerValue,
        now: Instant): Op[SurveyEvent, SurveyError, QuestionAnswer] = surveyOps.answerEmail(
      user,
      survey,
      value,
      now)

  extension (user: User[State.AnsweringQuestion])

    def answerProfiling(
        survey: Survey[SurveyState.WithProfilingQuestion],
        value: AnswerValue,
        now: Instant): Op[SurveyEvent, SurveyError, QuestionAnswer] = surveyOps.answerProfiling(
      user,
      survey,
      value,
      now)

    def answerLocation(
        survey: Survey[SurveyState.WithLocationQuestion],
        value: AnswerValue,
        now: Instant): Op[SurveyEvent, SurveyError, QuestionAnswer] = surveyOps.answerLocation(
      user,
      survey,
      value,
      now)

    def answerAdvertiser(
        survey: Survey[SurveyState.WithAdvertiserQuestion],
        value: AnswerValue,
        now: Instant): Op[SurveyEvent, SurveyError, QuestionAnswer] = surveyOps.answerAdvertiser(
      user,
      survey,
      value,
      now)
  end extension
end ops
