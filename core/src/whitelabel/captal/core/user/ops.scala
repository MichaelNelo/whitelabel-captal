package whitelabel.captal.core.user

import java.time.Instant

import whitelabel.captal.core.application.{NextStep, Phase}
import whitelabel.captal.core.survey.question.{
  AnswerValue,
  FullyQualifiedQuestionId,
  QuestionAnswer
}
import whitelabel.captal.core.survey.{
  AdvertiserId,
  Error as SurveyError,
  Event as SurveyEvent,
  State as SurveyState,
  Survey,
  ops as surveyOps
}
import whitelabel.captal.core.{Op, survey, video}

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

    def assignVideo(
        videoId: video.Id,
        advertiserId: Option[AdvertiserId],
        videoType: video.VideoType,
        now: Instant): UserOp[User[State.WatchingVideo]] =
      val event = Event.VideoAssigned(user.id, videoId, advertiserId, videoType, now)
      val newUser = user.copy[State.WatchingVideo](state = State.WatchingVideo(videoId))
      Op.emit(event, newUser)
  end extension

  extension (user: User[State.WatchingVideo])
    def assignVideoSurvey(
        advertiserId: Option[AdvertiserId],
        nextQuestion: Option[FullyQualifiedQuestionId],
        terminalPhase: Phase,
        now: Instant): UserOp[User[State.AnsweringVideoSurvey] | NextStep] =
      nextQuestion match
        case Some(question) =>
          val event = Event.VideoSurveyAssigned(user.id, advertiserId, question, now)
          val newUser = user.copy[State.AnsweringVideoSurvey](state = State.AnsweringVideoSurvey(
            advertiserId,
            question.surveyId,
            question.questionId))
          Op.emit(event, newUser)
        case None =>
          Op.pure(NextStep(terminalPhase))
  end extension

  extension (user: User[State.AnsweringVideoSurvey])
    def answerAdvertiserSurvey(
        survey: Survey[SurveyState.WithAdvertiserQuestion],
        value: AnswerValue,
        now: Instant): Op[SurveyEvent, SurveyError, QuestionAnswer] = surveyOps.answerAdvertiser(
      user.asAnswering,
      survey,
      value,
      now)

  extension (user: User[State.AnsweringVideoSurvey])
    private def asAnswering: User[State.AnsweringQuestion] = User[State.AnsweringQuestion](
      user.id,
      State.AnsweringQuestion(user.state.surveyId, user.state.questionId))

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

  extension (user: User[State.Ready])
    /** The Ready → Authorized transition, emitting UserFinishedProcess. The returned
      * `User[State.Authorized]` is in-memory only: `session.phase` stays `Ready` until
      * `UnifiAccessHandler` confirms with the Controller post-commit, so a failed grant leaves the
      * user retryable through `/api/finish`.
      */
    def finish(now: Instant): UserOp[User[State.Authorized]] =
      val authorized = user.copy[State.Authorized](state = State.Authorized(
        user.state.redirectUrl,
        user.state.watchedVideoId,
        user.state.answeredQuestionIds))
      val event = Event.UserFinishedProcess(
        user.id,
        user.state.watchedVideoId,
        user.state.answeredQuestionIds,
        now)
      Op.emit(event, authorized)
  end extension

  extension (user: User[State.Authorized])
    /** End of the granted access: emits AccessExpired. Returns no destination state — expiring ends
      * the flow instead of moving to another step, and does not revert to `State.Guest`: re-entry
      * is re-derived by `ProvideNextIdentificationSurveyHandler` from the surviving `users` row.
      */
    def expire(now: Instant): UserOp[Event.AccessExpired] =
      // The annotation is required: an enum case constructor application infers the parent type
      // `Event`, and the return value has to keep the case's own type.
      val event: Event.AccessExpired = Event.AccessExpired(user.id, now)
      Op.emit(event, event)
  end extension
end ops
