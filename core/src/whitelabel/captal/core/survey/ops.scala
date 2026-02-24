package whitelabel.captal.core.survey

import java.time.Instant

import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.Op.given
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.user.{State as UserState, User}

object ops:
  type SurveyOp[A] = Op[SurveyEvent, Error, A]

  def answerEmail(
      user: User[UserState.WithEmail],
      survey: Survey[State.WithEmailQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- question.ops.validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, now)
      questionEvent = QuestionEvent.EmailQuestionAnswered(
        userId = user.id,
        surveyId = survey.id,
        questionId = q.id,
        answer = answer,
        occurredAt = now)
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result

  def answerProfiling(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithProfilingQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- question.ops.validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, now)
      questionEvent = QuestionEvent.ProfilingQuestionAnswered(
        userId = user.id,
        surveyId = survey.id,
        questionId = q.id,
        answer = answer,
        occurredAt = now)
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result

  def answerLocation(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithLocationQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- question.ops.validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, now)
      questionEvent = QuestionEvent.LocationQuestionAnswered(
        userId = user.id,
        surveyId = survey.id,
        questionId = q.id,
        hierarchyLevel = survey.state.hierarchyLevel,
        answer = answer,
        occurredAt = now)
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result
  end answerLocation

  def answerAdvertiser(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithAdvertiserQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- question.ops.validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, now)
      questionEvent = QuestionEvent.AdvertiserQuestionAnswered(
        userId = user.id,
        surveyId = survey.id,
        advertiserId = survey.state.advertiserId,
        questionId = q.id,
        answer = answer,
        occurredAt = now)
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result
  end answerAdvertiser
end ops
