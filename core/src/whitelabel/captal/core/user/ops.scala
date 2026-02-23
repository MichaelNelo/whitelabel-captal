package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.Op
import whitelabel.captal.core.survey.question.{AnswerValue, QuestionAnswer}
import whitelabel.captal.core.survey.{
  Error as SurveyError,
  Event as SurveyEvent,
  State as SurveyState,
  Survey,
  ops as surveyOps
}

object ops:
  type UserOp[A] = Op[Event, Error, A]

  def createWithEmail(
      sessionId: SessionId,
      deviceId: DeviceId,
      locale: String,
      email: Email,
      now: Instant): UserOp[User[State.WithEmail]] =
    val userId = Id.generate
    val user = User[State.WithEmail](userId, State.WithEmail(email, sessionId, locale))
    val event = Event.UserCreated(userId, email, sessionId, deviceId, locale, now)
    Op.emit(event, user)

  extension (user: User[State.WithEmail])
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
