package whitelabel.captal.platform.eventhandlers

import whitelabel.captal.core.application.EventHandler
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import whitelabel.captal.telemetry.Tracing

/** An event handler in a span of its own, named after it: in a trace, which handler a write came
  * from, and what it cost.
  */
object TracedEventHandler:
  def apply[E](name: String, tracing: Tracing)(
      handler: EventHandler[Task, E]): EventHandler[Task, E] =
    events => tracing.span(s"events $name")(handler.handle(events))

  /** The same, for a handler that writes inside the chain's transaction. */
  def db[E](name: String, tracing: Tracing)(handler: DbEventHandler[E]): DbEventHandler[E] =
    (events, quill: QuillSqlite) => tracing.span(s"events $name")(handler.handle(events, quill))
