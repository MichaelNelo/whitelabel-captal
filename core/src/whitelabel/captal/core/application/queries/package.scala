package whitelabel.captal.core.application.queries

import whitelabel.captal.core.application.Error

trait Query[F[_], Q]:
  type Result
  def execute(query: Q): F[Either[Error, Result]]

object Query:
  type Aux[F[_], Q, R] =
    Query[F, Q] {
      type Result = R
    }
  def apply[F[_], Q](using q: Query[F, Q]): Query[F, Q] = q
