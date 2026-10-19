package whitelabel.captal.api.session

import io.getquill.*
import whitelabel.captal.core.user
import whitelabel.captal.platform.UserRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import zio.*

/** Direct id-based lookup, used to validate the cross-location `captal_user` cookie. Separate from
  * `UserRepository` (state-based, tied to the `SessionContext`) so routes that do not yet have a
  * session can use it.
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
