package whitelabel.captal.infra.repositories

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.user
import whitelabel.captal.core.user.{State, User}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.users.given
import whitelabel.captal.infra.session.SessionContext
import whitelabel.captal.infra.{AnswerRow, QuestionRow, SessionRow, UserRow}
import zio.*

object UserRepositoryQuill:
  inline def findWithEmailByIdQuery = quote: (userIdParam: user.Id) =>
    query[User[State.WithEmail]].filter(_.id == userIdParam)

  // Query that gets the user with surveyId/questionId from the CURRENT session
  inline def findAnsweringBySessionQuery = quote:
    (userIdParam: user.Id, sessionIdParam: user.SessionId) =>
      for
        userRow <- query[UserRow].filter(_.id == userIdParam)
        session <- query[SessionRow]
        if session.id == sessionIdParam && session.userId.contains(userIdParam) &&
          session.currentSurveyId.isDefined && session.currentQuestionId.isDefined
      yield (userRow.id, session.currentSurveyId, session.currentQuestionId)

  // Query that returns the (surveyId, questionId) pairs the user has answered
  //
  // Feeds State.Ready/State.Authorized.answeredQuestionIds, whose only consumer is
  // Event.UserFinishedProcess -> EventLogHandler -> the event_log row. It is an audit payload; it
  // decides nothing. What the flow asks next comes from a different subquery, over `answers` alone,
  // in SurveyRepositoryQuill.nextIdentificationSurveyQuery.
  //
  // So it deliberately does NOT filter on is_active: retiring a question in the backoffice must not
  // rewrite the record of what a user actually answered. (Note the join against `questions` here is
  // also why a hard delete would silently drop answers from the audit trail.)
  inline def findAnsweredQuestionsByUserQuery = quote: (userIdParam: user.Id) =>
    for
      a <- query[AnswerRow]
      if a.userId == userIdParam
      q <- query[QuestionRow]
      if q.id == a.questionId
    yield (q.surveyId, q.id)

  def apply(quill: QuillSqlite, ctx: SessionContext): UserRepository[Task] =
    new UserRepository[Task]:
      import quill.*

      def findWithEmail(): Task[Option[User[State.WithEmail]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          sessionData.userId match
            case Some(userId) =>
              run(findWithEmailByIdQuery(lift(userId))).map(_.headOption).orDie
            case None =>
              ZIO.none

      def findAnswering(): Task[Option[User[State.AnsweringQuestion]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          sessionData.userId match
            case Some(userId) =>
              run(findAnsweringBySessionQuery(lift(userId), lift(sessionData.sessionId)))
                .map(
                  _.headOption
                    .map { case (id, surveyIdOpt, questionIdOpt) =>
                      User[State.AnsweringQuestion](
                        id,
                        State.AnsweringQuestion(surveyIdOpt.get, questionIdOpt.get))
                    })
                .orDie
            case None =>
              ZIO.none

      def findWatchingVideo(): Task[Option[User[State.WatchingVideo]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          sessionData.userId match
            case Some(userId) =>
              sessionData.currentVideoId match
                case Some(videoId) =>
                  run(findWithEmailByIdQuery(lift(userId)))
                    .map(
                      _.headOption
                        .map(u => User[State.WatchingVideo](u.id, State.WatchingVideo(videoId))))
                    .orDie
                case None =>
                  ZIO.none
            case None =>
              ZIO.none

      def findAnsweringVideoSurvey(): Task[Option[User[State.AnsweringVideoSurvey]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          sessionData.userId match
            case Some(userId) =>
              // The question is what decides whether there is anything to answer. The advertiser
              // travels along; a promo's survey has none, and requiring both meant the answer was
              // refused with nothing said about why.
              sessionData.currentQuestion match
                case Some(question) =>
                  run(findWithEmailByIdQuery(lift(userId)))
                    .map(
                      _.headOption
                        .map(u =>
                          User[State.AnsweringVideoSurvey](
                            u.id,
                            State.AnsweringVideoSurvey(
                              sessionData.currentAdvertiserId,
                              question.surveyId,
                              question.questionId))))
                    .orDie
                case None =>
                  ZIO.none
            case None =>
              ZIO.none

      def findAuthorizedUser(): Task[Option[User[State.Authorized]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          if sessionData.phase != Phase.Authorized then
            ZIO.none
          else
            sessionData.userId match
              case None =>
                ZIO.none
              case Some(userId) =>
                run(findWithEmailByIdQuery(lift(userId)))
                  .map(_.headOption)
                  .orDie
                  .flatMap:
                    case None =>
                      ZIO.none
                    case Some(_) =>
                      run(findAnsweredQuestionsByUserQuery(lift(userId)))
                        .map: rows =>
                          val answeredQuestionIds = rows.map { case (sid, qid) =>
                            FullyQualifiedQuestionId(sid, qid)
                          }
                          Some(
                            User[State.Authorized](
                              userId,
                              State.Authorized(
                                sessionData.redirectUrl,
                                sessionData.currentVideoId,
                                answeredQuestionIds)))
                        .orDie

      def findReadyUser(): Task[Option[User[State.Ready]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          if sessionData.phase != Phase.Ready then
            ZIO.none
          else
            sessionData.userId match
              case None =>
                ZIO.none
              case Some(userId) =>
                run(findWithEmailByIdQuery(lift(userId)))
                  .map(_.headOption)
                  .orDie
                  .flatMap:
                    case None =>
                      ZIO.none
                    case Some(_) =>
                      run(findAnsweredQuestionsByUserQuery(lift(userId)))
                        .map: rows =>
                          val answeredQuestionIds = rows.map { case (sid, qid) =>
                            FullyQualifiedQuestionId(sid, qid)
                          }
                          Some(
                            User[State.Ready](
                              userId,
                              State.Ready(
                                sessionData.redirectUrl,
                                sessionData.currentVideoId,
                                answeredQuestionIds)))
                        .orDie

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, UserRepository[Task]] = ZLayer
    .fromFunction(apply)
end UserRepositoryQuill
