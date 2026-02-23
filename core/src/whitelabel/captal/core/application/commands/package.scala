package whitelabel.captal.core.application.commands

import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}

type Op[A] = CoreOp[Event, Error, A]

trait Handler[F[_], C]:
  type Result
  def handle(command: C): F[Op[Result]]

object Handler:
  type Aux[F[_], C, R] =
    Handler[F, C] {
      type Result = R
    }
  def apply[F[_], C](using h: Handler[F, C]): Handler[F, C] = h
