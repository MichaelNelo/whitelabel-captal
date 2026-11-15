package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.user

/** Sessions by id, outside any session: what entering the portal reads to resume the one a cookie
  * names.
  */
trait SessionRepository[F[_]]:
  def find(id: user.SessionId): F[Option[SessionData]]

/** Users by id, outside any session: whether the one a cookie names is still known. */
trait UserDirectory[F[_]]:
  def exists(id: user.Id): F[Boolean]
