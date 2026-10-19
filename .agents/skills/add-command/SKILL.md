---
name: add-command
description: Use when adding a new command + handler in the application layer (core/application/commands/). Covers the Handler trait, Op monad, event emission, error handling, and wiring as a Flow in api/Flows.scala and api/AppLayers.scala. Triggers on "add command", "new command handler", "command pattern", "nuevo command", "agregar handler".
version: 1.1.0
---

# Add a command + handler

## Mental model

The application layer is a **pure command/event system**:

- A **command** is a case class describing intent (`AnswerEmailCommand`, `ProvideNextVideoCommand`).
- A **handler** transforms the command into an `Op[Event, Error, Result]` — a pure value carrying the events to emit and the result, or accumulated errors.
- A **flow** wires a handler with an event-handler chain: it runs the handler, dispatches events transactionally, and returns the result.
- Side effects (DB reads/writes) live in the `EventHandler` chain (`platform/eventhandlers/`), not in the handler itself.

Read `core/src/whitelabel/captal/core/Op.scala` and `core/src/whitelabel/captal/core/application/Flow.scala` if `Op`/`Flow` are unfamiliar. Brief refresher:

- `Op[A]` (alias in `commands/package.scala`) = `Op[Event, Error, A]`. Fail-fast under `flatMap`, accumulates errors under `parMapN`.
- Builders: `Op.pure`, `Op.emit(event)`, `Op.emit(event, value)`, `Op.fail(err)`, `Op.failIf(cond, err)`, `Op.fromEither`.
- `Flow.execute(cmd)` calls `handler.handle(cmd)`, runs the resulting `Op`, dispatches events, returns the result inside `F[_]` (typically `Task`).

## Steps

### 1. Pick the file location

Each command lives in its own file under `core/src/whitelabel/captal/core/application/commands/<Name>.scala`. Naming conventions:

- `Provide<X>Command` — query-like command that emits "viewed" events as side effect (e.g. `ProvideNextVideoCommand`).
- `Answer<X>Command` — user submits an answer (`AnswerEmailCommand`, `AnswerProfilingCommand`).
- `Mark<X>Command` — user signals state transition (`MarkVideoWatchedCommand`).

### 2. Define the command

```scala
package whitelabel.captal.core.application.commands

import java.time.Instant
import whitelabel.captal.core.survey.question.AnswerValue

final case class AnswerBonusCommand(answer: AnswerValue, occurredAt: Instant)
```

Field conventions: include `occurredAt: Instant` for any user-triggered command (the timestamp gets propagated into events).

### 3. Define the handler

In the same file, an object with the handler factory:

```scala
import cats.Monad
import cats.syntax.functor.*
import whitelabel.captal.core
import whitelabel.captal.core.Op.{convertError, convertEvent, given}
import whitelabel.captal.core.application.conversions.given
import whitelabel.captal.core.application.{Error, Event, NextStep}
import whitelabel.captal.core.infrastructure.SurveyRepository
import whitelabel.captal.core.user.ops as UserOps

object AnswerBonusHandler:
  def apply[F[_]: Monad](
      surveyRepo: SurveyRepository[F],
      nextStep: NextStep
  ): Handler.Aux[F, AnswerBonusCommand, NextStep] =
    new Handler[F, AnswerBonusCommand]:
      type Result = NextStep

      def handle(cmd: AnswerBonusCommand): F[core.Op[Event, Error, Result]] =
        surveyRepo.findCurrentUser().map:
          case None =>
            core.Op.fail(Error.UserNotIdentified)
          case Some(user) =>
            // UserOps.answerBonus returns an aggregate-level Op[user.Event, user.Error, A] —
            // the same shape as the real UserOps.answerEmail/answerProfiling/... — not an Either.
            // .convertEvent.convertError widens it into the application-level Event/Error the
            // rest of the pipeline speaks, exactly like `user.finish(now).convertEvent.convertError`
            // does in FinishHandler.
            UserOps.answerBonus(user, cmd.answer, cmd.occurredAt).convertEvent.convertError.as(nextStep)
```

Key points:
- `Handler[F, C]` is from `commands/package.scala`. The type member `Result` is fixed by `Aux`.
- `F[_]: Monad` is abstract — at the call site it's `Task`. In core tests we use `cats.Id`.
- The handler body returns `F[Op[Event, Error, Result]]`. The outer `F` is for impure reads (DB lookups via repositories); the inner `Op` is for pure validation + event emission.
- An aggregate op (`user.ops`, `survey.ops`, `video` equivalents) is typed in that aggregate's own
  event/error ADTs. `.convertEvent.convertError` — from `Op.scala`'s extensions, using the
  `Conversion` instances `application/conversions.scala` supplies — is what lifts it to the
  application-level `Op[Event, Error, A]` a `Handler` returns. There is no `.toAppError` method;
  don't invent one.
- Repositories live in `core/infrastructure/<Name>Repository.scala` and are abstract over `F[_]`.

### 4. Wire the Flow layer in `Flows.scala` and `AppLayers.scala`

`api/src/whitelabel/captal/api/Main.scala` is only the startup sequence now — resolve settings,
start the scheduler, log, assemble, serve. Layer definitions live in two other files (see
`docs/api.adoc`'s "Layer composition"):

In `api/src/whitelabel/captal/api/Flows.scala`, add one `ZLayer` per command, next to
`answerEmailFlowLayer` and the rest:

```scala
val answerBonusFlowLayer: ZLayer[
  SurveyRepository[Task] & EventHandler[Task, Event],
  Nothing,
  Flow.Aux[Task, AnswerBonusCommand, NextStep]
] = ZLayer.fromFunction:
  (surveyRepo: SurveyRepository[Task], eventHandler: EventHandler[Task, Event]) =>
    Flow(AnswerBonusHandler(surveyRepo, NextStep(Phase.Ready)), eventHandler)
```

Then add `Flows.answerBonusFlowLayer` to the `ZLayer.makeSome[...]` call in
`api/src/whitelabel/captal/api/AppLayers.scala`'s `appLayers`, and add
`Flow.Aux[Task, AnswerBonusCommand, NextStep]` to that file's `FullEnv` type alias. Also add the
same layer to `api/test/src/whitelabel/captal/api/TestLayers.scala` so tests cover the new flow —
it keeps its own copy of the layer list, not a reference to `Flows`.

### 5. Expose at the API edge

Create or reuse a Routes class (see skill `add-api-endpoint`) that resolves this Flow inside a
zio-http `Endpoint`'s `.implement` body — not a tapir `serverLogic`, which this codebase no longer
has:

```scala
createBonus.implement: (cookie, request) =>
  for
    _      <- SessionEndpoint.authorise(cookieConfig.valueFrom(cookie), OnMissing.Fail, Seq(Phase.X))
    flow   <- ZIO.service[Flow.Aux[Task, AnswerBonusCommand, NextStep]]
    now    <- Clock.instant
    result <- asApiError(flow.execute(AnswerBonusCommand(request.answer, now)))
  yield result
```

Add the `Flow.Aux` type to your route class's own `FullEnv` so the widen works when
`AppLayers.FullEnv` pulls it in — see skill `add-api-endpoint` for the full route shape.

### 6. Tests

Two layers:

- **Pure**: `core/test/.../<Name>HandlerSpec.scala` using `cats.Id` as the effect monad. Build mock repos that return `Id[Option[User]]` etc., call `handler.handle(cmd)`, assert on `Op.run` output (events + value, or errors).
- **E2E**: `api/test/.../suites/<Name>Suite.scala` hits the endpoint via `TestHelpers.testBackend` and asserts on DB state after.

## Patterns to follow

### Validation
```scala
Op.failUnless(cmd.amount > 0, Error.InvalidAmount(cmd.amount))
```

### Sequencing pure ops
```scala
for
  _   <- Op.failUnless(predicate, error)
  evt = MyEvent(...)
yield Op.emit(evt, result)
```

### Reading DB inside a handler
```scala
// Outer F[_] — Task in production, Id in tests
for
  maybeRow <- repo.findById(cmd.id)
yield Op.fromOption(maybeRow, Error.NotFound(cmd.id))
```

### Returning a phase-transition result
The result type is often `NextStep(phase: Phase)`. The route then calls `setPhase` based on the result.

## Anti-patterns

- ❌ DB writes from inside `handle` — those belong in event handlers (`platform/eventhandlers/`). Handlers should be pure + read-only.
- ❌ Throwing exceptions — use `Op.fail` so errors compose with the rest of the pipeline.
- ❌ Embedding `Task.attempt(...)` in the handler body — keeps the `F[_]` abstract. If you need a side effect, it goes in an event handler.
- ❌ Skipping `TestLayers.scala` registration — tests will provide-fail at compile time once you reference the new flow type in `Routes.FullEnv`.
- ❌ Mixing command kinds (e.g., an `Answer*` that also emits a `ProvideNext*` style event). Keep one responsibility per command.
