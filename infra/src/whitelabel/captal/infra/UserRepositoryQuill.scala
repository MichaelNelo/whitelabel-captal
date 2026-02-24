package whitelabel.captal.infra

import io.getquill.*
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.user.{State, User}
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.QuillSchema.given
import zio.*

object UserRepositoryQuill:
  inline def findByIdQuery = quote: (id: String) =>
    query[UserRow].filter(_.id == id)

  def apply(quill: QuillSqlite, ctx: SessionContext): UserRepository[Task] =
    new UserRepository[Task]:
      import quill.*

      def findWithEmail(): Task[Option[User[State.WithEmail]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          sessionData.userId match
            case Some(userId) =>
              run(findByIdQuery(lift(userId.asString)))
                .map(_.headOption.flatMap(toWithEmailUser))
                .orDie
            case None =>
              ZIO.none

      def findAnswering(): Task[Option[User[State.AnsweringQuestion]]] = ctx
        .getOrFail
        .flatMap: sessionData =>
          (sessionData.userId, sessionData.currentSurveyId, sessionData.currentQuestionId) match
            case (Some(userId), Some(surveyId), Some(questionId)) =>
              run(findByIdQuery(lift(userId.asString)))
                .map(_.headOption.flatMap(toAnsweringUser(_, surveyId, questionId)))
                .orDie
            case _ =>
              ZIO.none

  private def toWithEmailUser(row: UserRow): Option[User[State.WithEmail]] =
    for
      userId <- user.Id.fromString(row.id)
      email  <- row.email.map(user.Email.unsafeFrom)
    yield User(userId, State.WithEmail(email))

  private def toAnsweringUser(
      row: UserRow,
      surveyId: survey.Id,
      questionId: survey.question.Id): Option[User[State.AnsweringQuestion]] =
    for userId <- user.Id.fromString(row.id)
    yield User(userId, State.AnsweringQuestion(surveyId, questionId))

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, UserRepository[Task]] =
    ZLayer.fromFunction(apply)
end UserRepositoryQuill
