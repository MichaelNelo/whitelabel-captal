package whitelabel.captal.core.application

import cats.Monad
import cats.syntax.apply.*

trait EventHandler[F[_], -E]:
  def handle(events: List[E]): F[Unit]

object EventHandler:
  extension [F[_]: Monad, E](self: EventHandler[F, E])
    def andThen(other: EventHandler[F, E]): EventHandler[F, E] =
      events => self.handle(events) *> other.handle(events)
