package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.user.{State, User}

trait UserRepository[F[_]]:
  def findWithEmail(): F[Option[User[State.WithEmail]]]
  def findAnswering(): F[Option[User[State.AnsweringQuestion]]]
  def findWatchingVideo(): F[Option[User[State.WatchingVideo]]]
  def findAnsweringVideoSurvey(): F[Option[User[State.AnsweringVideoSurvey]]]
  def findReadyUser(): F[Option[User[State.Ready]]]

  /** The current session's user, once that session is `Phase.Authorized`.
    *
    * Counterpart of [[findReadyUser]] that closes the cycle. Returns `None` when the session is no
    * longer authorized — which is what makes stale expiration jobs harmless: if the user redid the
    * flow and got re-authorized, the old job finds nothing and emits nothing.
    */
  def findAuthorizedUser(): F[Option[User[State.Authorized]]]
