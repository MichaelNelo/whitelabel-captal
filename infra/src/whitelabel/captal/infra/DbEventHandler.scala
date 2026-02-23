package whitelabel.captal.infra

import cats.kernel.Monoid
import io.getquill.NamingStrategy
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.jdbczio.Quill
import zio.*

trait DbEventHandler[D <: SqlIdiom, N <: NamingStrategy, -E]:
  def handle(events: List[E], quill: Quill[D, N]): Task[Unit]

object DbEventHandler:
  given [D <: SqlIdiom, N <: NamingStrategy, E]: Monoid[DbEventHandler[D, N, E]] with
    def empty: DbEventHandler[D, N, E] =
      new DbEventHandler[D, N, E]:
        def handle(events: List[E], quill: Quill[D, N]): Task[Unit] = ZIO.unit

    def combine(
        a: DbEventHandler[D, N, E],
        b: DbEventHandler[D, N, E]
    ): DbEventHandler[D, N, E] =
      new DbEventHandler[D, N, E]:
        def handle(events: List[E], quill: Quill[D, N]): Task[Unit] =
          a.handle(events, quill) *> b.handle(events, quill)

  extension [D <: SqlIdiom, N <: NamingStrategy, E](self: DbEventHandler[D, N, E])
    def andThen(other: DbEventHandler[D, N, E]): DbEventHandler[D, N, E] =
      Monoid[DbEventHandler[D, N, E]].combine(self, other)
