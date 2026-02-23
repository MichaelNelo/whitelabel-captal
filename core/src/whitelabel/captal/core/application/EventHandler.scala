package whitelabel.captal.core.application

import cats.Monad
import cats.kernel.Monoid
import cats.syntax.apply.*

trait EventHandler[F[_], -E]:
  def handle(events: List[E]): F[Unit]

object EventHandler:
  given [F[_]: Monad, E]: Monoid[EventHandler[F, E]] with
    def empty: EventHandler[F, E] = _ => Monad[F].pure(())

    def combine(a: EventHandler[F, E], b: EventHandler[F, E]): EventHandler[F, E] =
      events => a.handle(events) *> b.handle(events)

  extension [F[_]: Monad, E](self: EventHandler[F, E])
    def andThen(other: EventHandler[F, E]): EventHandler[F, E] =
      Monoid[EventHandler[F, E]].combine(self, other)
