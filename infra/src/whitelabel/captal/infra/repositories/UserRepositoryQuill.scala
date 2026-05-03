package whitelabel.captal.infra.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.user
import whitelabel.captal.core.user.{State, User}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.users.given
import whitelabel.captal.infra.session.SessionContext
import whitelabel.captal.infra.{SessionRow, UserRow}
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
              (sessionData.currentQuestion, sessionData.currentAdvertiserId) match
                case (Some(question), Some(advertiserId)) =>
                  run(findWithEmailByIdQuery(lift(userId)))
                    .map(
                      _.headOption
                        .map(u =>
                          User[State.AnsweringVideoSurvey](
                            u.id,
                            State.AnsweringVideoSurvey(
                              advertiserId,
                              question.surveyId,
                              question.questionId))))
                    .orDie
                case _ =>
                  ZIO.none
            case None =>
              ZIO.none

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, UserRepository[Task]] = ZLayer
    .fromFunction(apply)
end UserRepositoryQuill
