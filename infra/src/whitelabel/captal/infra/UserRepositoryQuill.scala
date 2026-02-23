package whitelabel.captal.infra

import io.getquill.*
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.jdbczio.Quill
import izumi.reflect.Tag
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.user.{State, User}
import whitelabel.captal.core.{survey, user}
import zio.*

object UserRepositoryQuill:
  inline def findByIdQuery = quote: (id: String) =>
    query[UserRow].filter(_.id == id)

  def apply[D <: SqlIdiom, N <: NamingStrategy](quill: Quill[D, N]): UserRepository[Task] =
    new UserRepository[Task]:
      import quill.*

      def findById(id: user.Id): Task[Option[User[State]]] =
        run(findByIdQuery(lift(id.asString))).map(_.headOption.map(toUser)).orDie

      def findAnswering(
          id: user.Id,
          questionId: survey.question.Id): Task[Option[User[State.AnsweringQuestion]]] =
        run(findByIdQuery(lift(id.asString)))
          .map(_.headOption.flatMap(toAnsweringUser(_, questionId)))
          .orDie

  private def toUser(row: UserRow): User[State] =
    val state =
      row.email match
        case None =>
          State.PendingEmail(user.SessionId.fromString(row.id).get, user.DeviceId(""), row.locale)
        case Some(email) =>
          State.WithEmail(
            user.Email.unsafeFrom(email),
            user.SessionId.fromString(row.id).get,
            row.locale)
    User(user.Id.fromString(row.id).get, state)

  private def toAnsweringUser(
      row: UserRow,
      questionId: survey.question.Id): Option[User[State.AnsweringQuestion]] =
    for
      userId    <- user.Id.fromString(row.id)
      sessionId <- user.SessionId.fromString(row.id)
    yield User(userId, State.AnsweringQuestion(sessionId, row.locale, questionId))

  def layer[D <: SqlIdiom: Tag, N <: NamingStrategy: Tag]
      : ZLayer[Quill[D, N], Nothing, UserRepository[Task]] = ZLayer.fromFunction(apply[D, N])
end UserRepositoryQuill
