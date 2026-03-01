package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.application.{NextStep, Phase}
import whitelabel.captal.core.survey.question.{
  AnswerValue,
  FullyQualifiedQuestionId,
  QuestionAnswer
}
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

  def createGuest(
      nextQuestion: Option[FullyQualifiedQuestionId],
      now: Instant): UserOp[User[State.Guest.type]] =
    val userId = Id.generate
    val event = Event.NewUserArrived(userId, nextQuestion, now)
    Op.emit(event, User(userId, State.Guest))

  extension (user: User[State.WithEmail])
    def assignSurvey(
        nextQuestion: Option[FullyQualifiedQuestionId],
        terminalPhase: Phase,
        now: Instant): UserOp[User[State.AnsweringQuestion] | NextStep] =
      nextQuestion match
        case Some(question) =>
          val event = Event.SurveyAssigned(user.id, question, now)
          val newUser = user.copy[State.AnsweringQuestion](state = State.AnsweringQuestion(
            question.surveyId,
            question.questionId))
          Op.emit(event, newUser)
        case None =>
          val event = Event.IdentificationCompleted(user.id, now)
          Op.emit(event, NextStep(terminalPhase))

    def answerEmail(
        survey: Survey[SurveyState.WithEmailQuestion],
        value: AnswerValue,
        now: Instant): Op[SurveyEvent, SurveyError, QuestionAnswer] = surveyOps.answerEmail(
      user,
      survey,
      value,
      now)
  end extension

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
