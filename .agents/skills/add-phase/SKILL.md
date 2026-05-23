---
name: add-phase
description: Use when extending the captive-portal state machine with a new Phase (between Welcome and Ready). Triggers on "add phase", "new phase", "extender state machine", "fase nueva".
version: 1.0.0
---

# Add a new Phase

## State machine overview

A `Phase` represents where the user is in the captive-portal flow. The current enum (`core/.../application/phase.scala`):

```
Welcome → IdentificationQuestion → AdvertiserVideo → AdvertiserVideoSurvey → AdvertiserQuestion → Ready
```

The phase is persisted on `sessions.phase`, advanced by `SessionPhaseHandler`, validated by `sessionEndpoint.secured(allowedPhases = ...)`, and mapped to a SPA route by `Router.phaseToPage`.

## Steps

### 1. Add the case to the enum

`core/src/whitelabel/captal/core/application/phase.scala`:

```scala
enum Phase:
  case Welcome
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserVideoSurvey
  case AdvertiserQuestion
  case BonusGame             // NEW — placed where it should appear in the flow
  case Ready
```

Also update `Phase.fromDbString` / `Phase.toDbString` if defined (some places serialize via case name; check the file).

### 2. Decide the transitions

Two places set the phase based on events:

- **`SessionPhaseHandler.scala`** (`infra/.../eventhandlers/`): transitions on identification answer + video watched. If your new phase comes after one of these, add the branch there.
- **Individual route serverLogic**: e.g. `VideoRoutes.NextVideo` sets `session.phase = step.phase` when no video matches. If your phase is reached by a route directly, update the route.

Example: if BonusGame goes after AdvertiserQuestion:

```scala
// In SessionPhaseHandler — assuming there's an AdvertiserAnswered event:
case Event.Advertiser(AdvertiserEvent.AnswerAccepted(_, _)) =>
  setPhase(Phase.BonusGame)   // was Phase.Ready
```

### 3. Wire the SPA route

`client/src/whitelabel/captal/client/Router.scala`:

```scala
enum Page:
  case Welcome
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserVideoSurvey
  case BonusGame              // NEW
  case Ready
  case Error

// Route pattern
private val bonusGameRoute: Route[Page.BonusGame.type, Unit] = Route.static(
  Page.BonusGame,
  root / "bonus" / endOfSegments,
  basePath = basePath)

// Add to router routes list, getPageTitle, serializePage, deserializePage

// phaseToPage:
case Phase.BonusGame => Page.BonusGame
```

Also extend `splitter.collectStatic(Page.BonusGame)(BonusGameView.render)`.

### 4. Create the view

`client/src/whitelabel/captal/client/views/BonusGameView.scala`. Use `ReadyView` or `WelcomeView` as templates — both are minimal-state views. Use `Layout(content = ..., footer = ..., isLoading = ...)`.

i18n keys (e.g. `bonusGame.title`, `bonusGame.cta`) need to be added to the `I18n` schema in `core/src/whitelabel/captal/core/i18n/I18n.scala` AND populated in `LocaleService.buildI18n`. Operators then add the keys to their `locations/<slug>/i18n/{es,en}.yaml`.

### 5. Add a new API endpoint (if needed)

If the phase needs a backend interaction (e.g., POST `/api/bonus-game/play`), follow `add-api-endpoint`. Use `sessionEndpoint.secured(allowedPhases = Seq(Phase.BonusGame))` so requests outside that phase return `WrongPhase`.

### 6. Add allowed-phase guards

Audit existing routes that take `allowedPhases`. If a route is supposed to be reachable from your new phase, add it:

```scala
sessionEndpoint.secured(allowedPhases = Seq(Phase.AdvertiserVideoSurvey, Phase.BonusGame))
```

### 7. Migration if persisting

If you need new columns to support the phase (e.g., bonus-game score), follow `add-migration`. The `phase` column itself is generic TEXT; no migration needed just for adding an enum case.

### 8. Update skills

Update `add-location` and `troubleshoot-deployment` if the new phase is operator-visible (e.g., shows up in logs, requires a new YAML).

## Tests to add

- **Core**: command emits event that triggers the phase transition.
- **Infra**: `SessionPhaseHandler` test that asserts the transition.
- **E2E**: full-flow test in `api/test/.../suites/` that walks through to the new phase and validates `StatusResponse.phase` returns it.

## Verification

After deploy:
- `curl https://.../slug/api/status` and walk the flow manually — confirm the phase transitions.
- `aws logs tail /ecs/captal-<slug>` for `setPhase` log lines.

## Anti-patterns

- ❌ Adding a phase without updating `phaseToPage` — Router crashes on the missing case.
- ❌ Skipping the `allowedPhases` audit — endpoints silently reject requests with `WrongPhase`.
- ❌ Hardcoding the phase string in queries instead of using the enum — refactor pain.
