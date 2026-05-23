---
name: add-event-handler
description: Use when adding a new domain event + handler in the event-sourcing pipeline (DB persistence, session updates, side effects). Triggers on "add event", "new event handler", "domain event", "event sourcing", "agregar evento".
version: 1.0.0
---

# Add a domain event + handler

## Event sourcing model

Commands produce **events** (in `core/`); events are processed by **handlers** (in `infra/eventhandlers/`) inside a single Quill transaction (`TransactionalEventHandler`). Handlers are pure functions of `(Event, SessionContext, Quill) → Task[Unit]`.

Each command's handler returns `Op[Event...]` which contains the events to dispatch. After the handler returns, `Flow.execute` runs the events through the registered chain of handlers.

## Steps

### 1. Add the event case

Pick the right enum based on aggregate:

- **User events** → `core/src/whitelabel/captal/core/user/Event.scala`
- **Survey events** → `core/src/whitelabel/captal/core/survey/Event.scala` (if it exists; otherwise sibling)
- **Video events** → `core/src/whitelabel/captal/core/video/Event.scala`
- **Cross-cutting** (session phase, etc.) → `core/src/whitelabel/captal/core/application/Event.scala`

```scala
// in user/Event.scala
enum UserEvent:
  case UserCreated(userId: user.Id, email: Email, createdAt: java.time.Instant)
  case UserLocaleChanged(userId: user.Id, locale: String, changedAt: java.time.Instant)  // NEW
```

The wrapper `Event` enum in `core/.../application/Event.scala` should expose your sub-enum via a `case User(e: UserEvent)` (or similar) if it doesn't already.

### 2. Emit the event from a command handler

In the relevant command handler (`core/.../application/commands/<Name>.scala`), the `handle` method returns `Op[Result]` which carries events:

```scala
def handle(cmd: ChangeLocaleCommand): Op[Done] =
  Op.pure(Done).withEvents(
    UserEvent.UserLocaleChanged(cmd.userId, cmd.locale, Instant.now))
```

### 3. Create the handler

In `infra/src/whitelabel/captal/infra/eventhandlers/<Name>Handler.scala`:

```scala
package whitelabel.captal.infra.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.user.UserEvent
import whitelabel.captal.infra.UserRow
import whitelabel.captal.infra.schema.{QuillSqlite, given}
import whitelabel.captal.infra.session.SessionContext
import zio.*

class UserLocaleChangedHandler(ctx: SessionContext) extends DbEventHandler[Event]:
  def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
    import quill.*
    val updates = events.collect:
      case Event.User(UserEvent.UserLocaleChanged(uid, locale, _)) =>
        (uid, locale)
    ZIO.foreachDiscard(updates) { (uid, locale) =>
      run(query[UserRow].filter(_.id == lift(uid)).update(_.locale -> lift(locale))).unit
    }
```

Use the existing handlers in `infra/.../eventhandlers/` as patterns: `UserPersistenceHandler`, `AnswerPersistenceHandler`, `SessionPhaseHandler` etc.

### 4. Register the handler in the chain

In `api/src/whitelabel/captal/api/Main.scala`, find `eventHandlerLayer` and `.andThen` your new handler:

```scala
val dbHandler = EventLogHandler(ctx)
  .andThen(AnswerPersistenceHandler(ctx))
  .andThen(UserPersistenceHandler(ctx))
  .andThen(UserLocaleChangedHandler(ctx))   // NEW
  .andThen(SessionPhaseHandler(ctx, ...))
  ...
```

Order matters: the chain runs sequentially; if your handler depends on rows another handler creates, register after it.

**Also** add the same `.andThen` in `api/test/src/whitelabel/captal/api/TestLayers.scala` `eventHandlerLayer` — otherwise tests don't exercise your handler.

### 5. Update SessionContext if needed

If your handler needs to read or mutate the current session (e.g., set `userId` after `UserCreated`), update `SessionContext` accordingly and call `ctx.set(updatedSession)` at the end of `handle`. Pattern in `UserPersistenceHandler.scala:63`.

### 6. Test

Two layers of test:

- **Core test** (`core/test/.../<command>Spec.scala`): assert the command emits the expected events.
- **E2E test** (`api/test/.../suites/<X>Suite.scala`): exercise the HTTP endpoint and assert the DB side effect (row updated, etc.) using Quill queries via `TestHelpers`.

## Common gotchas

- **Forgetting `TestLayers` update**: tests pass locally but the new behavior is missing in prod because the handler chain in tests vs prod diverged. Always update both.
- **Reading from DB inside handler**: prefer reading from `SessionContext` if the value was already loaded. Each query inside the transaction adds rqlite roundtrip cost.
- **Order dependencies**: if handler A needs a row B created, register B's handler first. The chain runs left-to-right.
- **Non-idempotent updates**: a row update that depends on previous state must be guarded against re-application (events can be replayed in theory). For now we don't replay, but write idempotent SQL where natural.

## Anti-patterns

- ❌ Side effects outside `handle` (e.g., starting a fiber, calling an external API non-transactionally) — breaks event-sourcing semantics. Use a separate non-transactional handler if you really need async.
- ❌ Throwing exceptions from `handle` — return a failing `Task` so the transaction rolls back.
- ❌ Emitting an event without a corresponding handler — silent no-op, debugging nightmare.
