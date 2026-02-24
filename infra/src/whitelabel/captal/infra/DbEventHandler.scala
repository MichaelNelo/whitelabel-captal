package whitelabel.captal.infra

import cats.kernel.Monoid
import zio.*

trait DbEventHandler[-E]:
  def handle(events: List[E], quill: QuillSqlite): Task[Unit]

object DbEventHandler:
  given [E]: Monoid[DbEventHandler[E]] with
    def empty: DbEventHandler[E] =
      new DbEventHandler[E]:
        def handle(events: List[E], quill: QuillSqlite): Task[Unit] = ZIO.unit

    def combine(a: DbEventHandler[E], b: DbEventHandler[E]): DbEventHandler[E] =
      new DbEventHandler[E]:
        def handle(events: List[E], quill: QuillSqlite): Task[Unit] =
          a.handle(events, quill) *> b.handle(events, quill)

  extension [E](self: DbEventHandler[E])
    def andThen(other: DbEventHandler[E]): DbEventHandler[E] =
      Monoid[DbEventHandler[E]].combine(self, other)
