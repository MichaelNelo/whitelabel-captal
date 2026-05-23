---
name: add-view
description: Use when adding a new Laminar view in client/views/. Covers Layout wrapper, signal-based reactivity, API calls on mount, Router integration, error handling. Triggers on "add view", "new spa screen", "nueva vista", "laminar view".
version: 1.0.0
---

# Add a Laminar view

## Mental model

Every view in `client/views/<Name>View.scala`:

- Returns an `HtmlElement` from `def render: HtmlElement`.
- Wraps content in `Layout(content, footer, isLoading)` — the project-wide shell with brand icon, loading state, footer slot.
- Reacts to state via Laminar signals; the view is a pure function of those signals.
- Loads data on mount via `onMountCallback { _ => loadFoo() }` + `Runtime.run`.
- Escalates API failures via `ErrorHandler.escalate(err)` (centralized error page).

Read existing views before adding: `WelcomeView` and `ReadyView` are the simplest; `IdentificationQuestionView` is the canonical pattern with state machine + validation + submit.

## Steps

### 1. Create the file

`client/src/whitelabel/captal/client/views/<Name>View.scala`:

```scala
package whitelabel.captal.client.views

import scala.concurrent.ExecutionContext.Implicits.global

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, ErrorHandler, Router, Runtime}
import whitelabel.captal.core.application.Phase

object BonusGameView:
  private val score: Var[Int] = Var(0)
  private val isSubmitting: Var[Boolean] = Var(false)

  def render: HtmlElement = Layout(
    isLoading = score.signal.map(_ < 0),    // contrived; usually a real loading signal
    content = div(
      cls := "bonus-game-view",
      h1(cls := "bonus-game-title", child.text <-- I18nClient.i18n.map(_.bonusGame.title)),
      p(cls := "bonus-game-score", child.text <-- score.signal.map(s => s"Score: $s")),
      onMountCallback { _ =>
        loadInitialScore()
      }
    ),
    footer = div(
      button(
        cls := "welcome-button",
        disabled <-- isSubmitting.signal,
        child.text <-- I18nClient.i18n.map(_.bonusGame.submit),
        onClick --> { _ => submit() }
      )
    )
  )

  private def loadInitialScore(): Unit = Runtime.run:
    ApiClient.getBonusScore().map:
      case Right(s)  => score.set(s)
      case Left(err) => ErrorHandler.escalate(err)

  private def submit(): Unit =
    isSubmitting.set(true)
    AppState.setNavigating(true)
    Runtime.run:
      ApiClient.submitBonus(score.now()).map:
        case Right(_) =>
          isSubmitting.set(false)
          Router.syncWithPhase(Phase.Ready)
          AppState.setNavigating(false)
        case Left(err) =>
          isSubmitting.set(false)
          AppState.setNavigating(false)
          ErrorHandler.escalate(err)
end BonusGameView
```

### 2. Register in the Router

`client/src/whitelabel/captal/client/Router.scala`:

```scala
enum Page:
  ...
  case BonusGame      // NEW

private val bonusRoute: Route[Page.BonusGame.type, Unit] = Route.static(
  Page.BonusGame, root / "bonus" / endOfSegments, basePath = basePath)

// Add bonusRoute to routes list, getPageTitle, serializePage, deserializePage
// Add to splitter:
.collectStatic(Page.BonusGame)(BonusGameView.render)
```

If the view is reached via a `Phase` transition, also add `case Phase.BonusGame => Page.BonusGame` in `phaseToPage` (see skill `add-phase`).

### 3. Add CSS

Edit `client/assets/styles.css`. Conventions:
- Class names: `<view-slug>-<purpose>` (e.g. `.bonus-game-view`, `.bonus-game-score`).
- **Reuse existing class hooks before inventing**: `.welcome-button` for primary CTAs, `.question-submit-button` for form submits, `.layout-view`, `.view-content`. See skill `style-reference`.
- Use CSS variables (`var(--color-primary)`, `var(--space-md)`) — operators can override at `:root`.

### 4. Add i18n keys

If your view shows translated copy:
- Add fields to `core/.../i18n/I18n.scala` (e.g. `final case class BonusGame(title: String, submit: String)` + add to `I18n` case class).
- Update `infra/.../services/LocaleService.scala` `buildI18n` to populate them.
- Update the bundled default `cli/resources/templates/location/i18n/{es,en}.yaml`.
- Update the operator-facing skill `i18n-reference` with the new keys.

Detailed flow: skill `add-i18n-key`.

### 5. Tests

There's no JS test runner in the project (just `./mill client.compile` for type-checking). Manual smoke test locally (see skill `run-locally`):

```bash
./mill clean client.generatedSources && ENVIRONMENT=dev ./mill client.bundle
./mill api.dev --slug bonus-test --dir locations/bonus-test --shared-dir shared
# Open http://localhost:8080/bonus/ and walk through
```

## Patterns to follow

### Reactive bindings
| Want | Use |
|---|---|
| Plain text from a Signal[String] | `child.text <-- mySignal` |
| Conditional element from Signal[Option[T]] | `child.maybe <-- sig.map(_.map(toElement))` |
| Switch elements from Signal[T] | `child <-- sig.map(elementFor)` |
| List of elements | `children <-- listSignal.split(_.id)(elementFor)` |
| Apply CSS class conditionally | `cls <-- sig.map(if _ then "active" else "")` |
| Apply attribute conditionally | `disabled <-- sig` |
| Combine multiple signals | `sigA.combineWith(sigB).map((a, b) => ...)` |

### State management
- **Cross-view state** → `AppState` (currentSurvey, error, locale, phase, isNavigating).
- **View-local UI state** (isSubmitting, validationError, isExpanded) → `private val foo: Var[T]` inside the view object.
- Never read `AppState` mutably from outside; only call its `setX` / `clearX` methods.

### Errors
- API call returns `Left(ApiError)` → `ErrorHandler.escalate(err)`. **Centralizado**, NO inline `serverError` Var.
- User input invalid (email mal formado, required field empty) → local `validationError: Var[Option[String]]` + `.validation-error` div inline. NO escalate.

### Lifecycle
- Mount → `onMountCallback { _ => loadInitialData() }`.
- Unmount → Laminar cleans up signal subscriptions automatically. Don't manually unbind.
- Mid-flow API call → wrap in `Runtime.run:` (escala Future failures auto).

### Navigation
- To another phase: `Router.syncWithPhase(phase)`.
- To error page: `ErrorHandler.escalate(err)` (preferred over manual `Router.navigateToError`).
- Direct page push (rare): `Router.navigateToError()` (only example) — add similar helpers in `Router.scala` if you need them.

## Common gotchas

- **Forgetting `onMountCallback`**: the view renders but `loadX` never runs. The user sees a blank state. Pattern always uses mount.
- **`Var` reads inside render returning stale value**: use `.signal.map(...)` not `.now()` for things that change. `.now()` only inside event handlers (onClick).
- **Hardcoded path** (`src := "/foo.svg"`): breaks under slug prefix. Always relative: `src := "foo.svg"`.
- **Class name conflict**: if you write `.bonus-button` but the operator's CSS overrides `.welcome-button`, your button isn't themed. Reuse existing hooks.

## Anti-patterns

- ❌ Returning `Element` from a non-`render` method without inputs — makes the view non-reusable. Pass signals as parameters if you need composition.
- ❌ Storing UI state in `AppState` (e.g. `isFooModalOpen`) — pollutes the global state. Local `Var`.
- ❌ Calling `dom.window.fetch` directly instead of `ApiClient.X` — bypasses error handling + tests.
- ❌ Inline `<style>` tags or `style :=` for theming — all theming lives in `assets/styles.css`. Inline only for view-specific layout that's not customizable (e.g. `styleAttr := "animation-delay: 0.5s"`).
- ❌ Forgetting `setNavigating(true)` before a multi-step transition — the user sees the page hang without feedback.
