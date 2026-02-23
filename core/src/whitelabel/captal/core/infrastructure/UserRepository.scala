package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.user.{State, User}
import whitelabel.captal.core.{survey, user}

trait UserRepository[F[_]]:
  def findById(id: user.Id): F[Option[User[State]]]
  def findAnswering(
      id: user.Id,
      questionId: survey.question.Id): F[Option[User[State.AnsweringQuestion]]]
