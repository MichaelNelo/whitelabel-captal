---
name: client-patterns
description: Reference for the Scala.js SPA conventions — Laminar signals, AppState, dom.fetch, cookies, BuildInfo, basePath. Use when working on any client/ change and want to follow project conventions. Triggers on "client patterns", "laminar conventions", "spa pattern", "client reference", "scala js patterns".
version: 1.0.0
---

# Client (Scala.js SPA) — reference

This is the orientation doc for working in `client/`. For adding a specific view see `add-view`; for a new translation key see `add-i18n-key`; for CSS see `style-reference`.

## Stack

- **Scala.js** 1.x via Mill `ScalaJSModule`
- **Laminar 17.x** — reactive UI lib (Var + Signal + EventStream)
- **Waypoint 9.x** — type-safe router built on Laminar
- **Circe** — JSON serde
- **scala-java-time** — `java.time.Instant` polyfill

No React, no Redux, no Tapir client interpreter. All HTTP is raw `dom.fetch`.

## State management — `AppState`

Single source of reactive truth for cross-view state. Pattern:

```scala
// AppState.scala
private val phaseVar: Var[Option[Phase]] = Var(None)
val phase: Signal[Option[Phase]] = phaseVar.signal
def setPhase(p: Phase): Unit = phaseVar.set(Some(p))
```

Always: `private val xxxVar: Var[T]` + `val xxx: Signal[T]` + `def setX(...)`/`clearX()`. Never expose the `Var` directly — read-only signal + named setters.

**What goes in AppState** (cross-view):
- `locale` — current language
- `phase` — current backend phase
- `currentSurvey` / `currentAdvertiserSurvey` — survey loaded for active question phase
- `isNavigating` — global "transition in progress" for the nav loader overlay
- `error` — last unhandled error (drives `ErrorView`)

**What stays local to a view**:
- `isSubmitting` (button disabled during POST)
- `validationError` (form input feedback)
- `isFullscreen`, `isPlaying` (video player UI state)
- Anything purely UI that doesn't outlive the view

## Reactivity — Laminar signals

| Pattern | Use case |
|---|---|
| `child.text <-- signal[String]` | Update text content from a signal |
| `child <-- signal.map(elementFor)` | Swap entire child element on signal value |
| `child.maybe <-- signal.map(_.map(toElement))` | Render conditionally (None → empty) |
| `children <-- listSignal.split(_.id)(elementFor)` | Render lists with stable identity |
| `cls <-- signal.map(if _ then "on" else "off")` | Toggle CSS class |
| `cls := "static"`, `cls(static)` | Static class (no signal) |
| `cls <-- sig.map("base " + _)` | Compose static + dynamic |
| `disabled <-- signal[Boolean]` | Bind attribute reactively |
| `value <-- signal[String]` | Two-way input binding (combine with `onInput`) |
| `onClick --> { _ => ... }` | Event handler |
| `signalA.combineWith(signalB).map((a, b) => ...)` | Combine 2+ signals |
| `onMountCallback { ctx => ... }` | Run effect on mount |
| `onUnmountCallback { ctx => ... }` | Run effect on unmount (rare; Laminar auto-cleans subs) |

**Reading current value imperatively** (e.g. inside `onClick`):
```scala
val current = myVar.now()
```
Only inside event handlers. For rendering use signals.

## HTTP — `ApiClient`

`client/.../ApiClient.scala` wraps `dom.fetch` and returns `Future[Either[ApiError, T]]`.

Pattern:
```scala
Runtime.run:
  ApiClient.getStatus(headers).map:
    case Right(status) =>
      // success: update AppState, navigate, etc.
    case Left(err) =>
      ErrorHandler.escalate(err)
```

`Runtime.run`:
- Drops the Future result (`Unit`).
- On Future-level failure (network timeout, decode crash) escalates to error page automatically.

`ApiClient` does NOT use the Tapir client interpreter — it builds requests manually. **The Tapir endpoint `cookie[Option[String]]("session_id")` inputs are IGNORED by the client**; the browser auto-handles cookies. This is intentional: the API can rename cookies without touching the client.

### Cookies

The client NEVER reads or writes cookies directly:

- `dom.fetch` uses default `credentials = same-origin` → browser auto-includes matching cookies.
- API sets cookies via `Set-Cookie` headers → browser stores them.
- No `document.cookie` access anywhere in the codebase. Don't add any.

Why: the client knows nothing about session cookie naming (`captal_session_<slug>`) or path scoping. Browser handles it. This makes API-side cookie changes (new format, new path) transparent.

## Routing — `Router`

Closed enum (`enum Page`) + Waypoint:

```scala
enum Page:
  case Welcome, IdentificationQuestion, AdvertiserVideo, AdvertiserVideoSurvey, Ready, Error
```

Each case has a `Route.static(Page.X, root / "x" / endOfSegments, basePath = basePath)`. `basePath` extraction:

```scala
private val basePath: String =
  dom.window.location.pathname.split("/").find(_.nonEmpty) match
    case Some(slug) if slug != "api" => s"/$slug"   // production: /<slug>/...
    case _                            => ""          // dev: /
```

In production the SPA is served from `/<slug>/index.html`, so all routes are prefixed with `/<slug>`. In dev no prefix.

`Router.syncWithPhase(phase)` — push state to the page matching the server's reported phase. Called from `Main.syncPhaseOnLoad` and after API responses that change phase.

`Router.navigateToError()` — go to `/error`. Used by `ErrorHandler.escalate`. **Page.Error lives outside the Phase machine** — no phase maps to it.

## i18n — `I18nClient`

```scala
I18nClient.i18n: Signal[I18n]        // current bundle
I18nClient.locale: Signal[String]    // current locale code
I18nClient.setLocale(loc: String)    // change locale; triggers re-fetch from API
I18nClient.current: I18n              // sync snapshot (use sparingly; prefer signal)
```

Bundle ist fetched from `/api/i18n/<locale>` on first call. Cached client-side per locale.

Use in views:
```scala
child.text <-- I18nClient.i18n.map(_.welcome.title)
```

Missing key returns `[welcome.title]` literal (from `LocaleService.buildI18n` fallback). To add new keys see skill `add-i18n-key`.

## Paths and `<base href>`

`index.html.template` injects `<base href="/<slug>/">` via inline script BEFORE any `<link>` / `<script>` tag. This makes relative paths resolve under `/<slug>/`.

**Rule**: ALWAYS use relative paths in views:

```scala
img(src := "brand-icon.svg")          // ✅ resolves to /<slug>/brand-icon.svg
img(src := "/brand-icon.svg")         // ❌ absolute, ignores <base>, breaks in prod
```

This applies to images, scripts, stylesheets, links, anything with a URL.

## ENVIRONMENT toggle — `BuildInfo`

`ENVIRONMENT=dev ./mill client.bundle` materializes `BuildInfo.isDevMode = true` at compile time. Used for dev-only UI (e.g. reset button on Ready page).

**Caveat**: Mill caches `client.generatedSources`. If you toggle `ENVIRONMENT` between builds, run `./mill clean client.generatedSources` first or the cached BuildInfo wins.

```scala
if BuildInfo.isDevMode then
  div(button(cls := "dev-reset-button", "Reset", onClick --> { _ => resetPhase() }))
else
  div()    // empty in prod
```

## UniFi captive-portal params

When UniFi redirects an unauthenticated client to the captive portal, the URL carries:

```
?id=<CLIENT_MAC>&ap=<AP_MAC>&ssid=<SSID>&url=<ORIGINAL_URL>&click_id=<TOKEN>&t=<TIMESTAMP>
```

`Main.parseCaptivePortalHeaders` reads these from `dom.URLSearchParams` and forwards as `X-*` headers on the first `/api/status` call. `t` (timestamp) is ignored.

| URL param  | Forwarded header | Required for CREATE |
|------------|------------------|---------------------|
| `id`       | `X-Client-Mac`   | yes                 |
| `click_id` | `X-Click-Id`     | yes                 |
| `ap`       | `X-Ap-Mac`       | no (soft-validated) |
| `ssid`     | `X-Ssid`         | no                  |
| `url`      | `X-Redirect-Url` | no                  |

The mapping table lives in `Main.scala:53` and is duplicated in `ErrorView.scala` for the retry button. If you add a new UniFi param, update both.

## Error handling

Three layers, all converge on `ErrorHandler.escalate(err)` → `/error` page:

1. **API call returns `Left(ApiError)`** → escalate. **NO inline `serverError` Var**.
2. **Future fails outright** (network timeout, decode crash, fetch rejection) → `Runtime.run` catches via `.failed.foreach` and escalates.
3. **Video `<video>` element fires `error` event** → handler escalates with `escalateMessage("Video load failed: ...")`.

**Validation errors are different**: user input that's malformed (invalid email, missing required field). Those use a local `validationError: Var[Option[String]]` + `.validation-error` div inline. NOT escalation — the user can fix and retry without leaving the view.

```scala
// Inline pattern (user input):
val validationError: Var[Option[String]] = Var(None)
div(child.maybe <-- validationError.signal.map(_.map(msg => div(cls := "validation-error", msg))))

// Escalation pattern (API/runtime):
case Left(err) => ErrorHandler.escalate(err)
```

## Layout wrapper

Every screen uses `Layout(content, footer, isLoading)`:

- `content: HtmlElement` — main slot
- `footer: HtmlElement` — bottom slot (CTA, locale picker, dev reset, etc.)
- `isLoading: Signal[Boolean] = Signal.fromValue(false)` — controls the loading-state visibility for the layout

Layout owns:
- The brand icon (`assets/brand-icon.svg`)
- The loading pulse animation (`.brand-pulse`)
- The fade-in/out transitions (`view-content.hidden` ↔ `.visible`)

Don't reinvent these in individual views.

## Build flow

```bash
./mill clean client.generatedSources       # invalidate BuildInfo cache
ENVIRONMENT=dev ./mill client.bundle       # produces out/client/bundle.dest/
./mill client.publishS3 --bucket captal-dev-assets --prefix bundle   # uploads to S3
```

Skill `run-locally` covers the full local dev loop. Skill `bump-cli-version` doesn't apply here — bundle is per-deployment, not per-CLI-version.

## Anti-patterns

- ❌ Adding a state to AppState that only one view reads — use a local `Var`.
- ❌ `dom.window.fetch` directly — bypasses error handling. Use `ApiClient`.
- ❌ `document.cookie.read / set` — break the project's invariant that cookies are opaque to the client.
- ❌ Absolute paths (`/foo.svg`) in views — breaks under slug prefix.
- ❌ Inline `style :=` for theming colors — use `cls := "..."` + CSS variables in `styles.css`.
- ❌ Importing JVM-only Java classes (`java.io.*`) — Scala.js link error.
- ❌ Treating Tapir endpoint values from `endpoints/` as the client's source of truth — the client only uses the DTO case classes, not the endpoint vals.
- ❌ Mutating `Var` from inside a Signal observer in render — causes Laminar reentrancy issues. Move mutations to event handlers.
