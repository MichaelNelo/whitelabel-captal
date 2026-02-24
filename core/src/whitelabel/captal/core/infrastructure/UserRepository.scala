package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.user.{State, User}

trait UserRepository[F[_]]:
  def findWithEmail(): F[Option[User[State.WithEmail]]]
  def findAnswering(): F[Option[User[State.AnsweringQuestion]]]
