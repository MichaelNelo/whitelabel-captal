package whitelabel.captal.infra.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.UserRepository
import whitelabel.captal.core.user
import whitelabel.captal.core.user.{State, User}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.users.given
import whitelabel.captal.infra.session.SessionContext
import zio.*

object UserRepositoryQuill:
  inline def findWithEmailByIdQuery = quote: (userIdParam: user.Id) =>
    query[User[State.WithEmail]].filter(_.id == userIdParam)

  inline def findAnsweringByIdQuery = quote: (userIdParam: user.Id) =>
    query[User[State.AnsweringQuestion]].filter(_.id == userIdParam)

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
              run(findAnsweringByIdQuery(lift(userId))).map(_.headOption).orDie
            case None =>
              ZIO.none

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, UserRepository[Task]] = ZLayer
    .fromFunction(apply)
end UserRepositoryQuill
