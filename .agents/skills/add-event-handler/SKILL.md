---
name: add-event-handler
description: Use when adding a new domain event + handler in the event-sourcing pipeline (DB persistence, session updates, side effects). Triggers on "add event", "new event handler", "domain event", "event sourcing", "agregar evento".
version: 1.0.0
---

# Add a domain event + handler

## Event sourcing model

Commands produce **events** (in `core/`); events are processed by **two kinds of handlers** (in `infra/eventhandlers/`):

1. **Transactional** (`DbEventHandler`): run inside a single Quill transaction (`TransactionalEventHandler`). Pure functions of `(Event, SessionContext, Quill) → Task[Unit]`. Use for DB writes that must commit atomically with the rest of the chain.
2. **Post-commit** (`EventHandler[Task, Event]`): composed via `.andThen` **after** `TransactionalEventHandler` in `Main.eventHandlerLayer`. Run only if the transaction committed. Use for external side-effects (HTTP calls, queue publishes) that should not roll back DB writes on failure. Example: `UnifiAuthorizationHandler` (UniFi Integration v1 two-step lookup + `AUTHORIZE_GUEST_ACCESS`).

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

In `api/src/whitelabel/captal/api/Main.scala`, find `eventHandlerLayer` and `.andThen` your new handler.

**Transactional (DB write)** — within the `dbHandler` chain:

```scala
val dbHandler = EventLogHandler(ctx)
  .andThen(AnswerPersistenceHandler(ctx))
  .andThen(UserPersistenceHandler(ctx))
  .andThen(UserLocaleChangedHandler(ctx))   // NEW (transactional)
  .andThen(SessionPhaseHandler(ctx, ...))
  ...
val transactional = TransactionalEventHandler(dbHandler, quill)
```

**Post-commit (external side-effect)** — after the transactional wrapper:

```scala
val transactional = TransactionalEventHandler(dbHandler, quill)
val unifiAuth = UnifiAuthorizationHandler(currentLocation.unifi, ctx, sessionService, client)
val anotherPostCommit = MyExternalApiHandler(...)
transactional.andThen(unifiAuth).andThen(anotherPostCommit)
```

Order matters: the chain runs sequentially; if your handler depends on rows another handler creates, register after it. Post-commit handlers run **only if** the transaction committed; their failures DO NOT roll back.

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

- ❌ Calling external APIs from a `DbEventHandler` — slow HTTP inside a DB transaction → connection-pool starvation. Move to a post-commit `EventHandler[Task, Event]` (pattern: `UnifiAuthorizationHandler`).
- ❌ Throwing exceptions from a transactional `handle` — return a failing `Task` so the rollback happens cleanly. For post-commit handlers, catch with `.either` and log; never let a side-effect failure crash the request.
- ❌ Emitting an event without a corresponding handler — silent no-op, debugging nightmare.
- ❌ Post-commit handler that re-emits events expecting the chain to re-run — there's no replay; design idempotency at the side-effect level (e.g., the UniFi Integration v1 `AUTHORIZE_GUEST_ACCESS` action is idempotent by clientId for the same window).
