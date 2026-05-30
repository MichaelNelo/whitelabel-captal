package whitelabel.captal.api

import io.getquill.*
import whitelabel.captal.core.user
import whitelabel.captal.infra.UserRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import zio.*

/** Direct id-based lookup for the cross-location `captal_user` cookie validation. Kept separate
  * from `UserRepository` (which is state-based and tied to the SessionContext) so it can be used in
  * the security logic of routes that don't yet have a session.
  */
trait UserLookup:
  def existsById(userId: user.Id): UIO[Boolean]

object UserLookup:
  val layer: ZLayer[QuillSqlite, Nothing, UserLookup] = ZLayer.fromFunction: (quill: QuillSqlite) =>
    new UserLookup:
      import quill.*

      def existsById(userId: user.Id): UIO[Boolean] =
        run(query[UserRow].filter(_.id == lift(userId)).take(1)).map(_.nonEmpty).orDie
end UserLookup
