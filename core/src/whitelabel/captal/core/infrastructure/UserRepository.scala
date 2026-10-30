package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.user.{Email, Id, State, User}

trait UserRepository[F[_]]:
  def findWithEmail(): F[Option[User[State.WithEmail]]]

  /** The user that already answered `email`, at any location. */
  def findIdByEmail(email: Email): F[Option[Id]]
  def findAnswering(): F[Option[User[State.AnsweringQuestion]]]
  def findWatchingVideo(): F[Option[User[State.WatchingVideo]]]
  def findAnsweringVideoSurvey(): F[Option[User[State.AnsweringVideoSurvey]]]
  def findReadyUser(): F[Option[User[State.Ready]]]

  /** The current session's user, once that session is `Phase.Authorized`; counterpart of
    * [[findReadyUser]]. `None` once the session is no longer authorized, which is what makes a
    * stale expiration job harmless.
    */
  def findAuthorizedUser(): F[Option[User[State.Authorized]]]
