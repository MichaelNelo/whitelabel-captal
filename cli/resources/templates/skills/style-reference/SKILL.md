---
name: style-reference
description: Reference for the CSS variables, class hooks, and per-screen DOM structure that locations can override via `assets/styles.css`. Lists the theming API (recommended), the per-page class anatomy, and shared/state modifiers. Use when customizing branding, colors, typography, layout, or specific screens (welcome, question, video, video-survey, ready). Triggers on "custom styles", "branding", "theme", "css variables", "override styles", "style screen", "estilos", "marca", "colores", "personalizar pantalla".
version: 1.1.0
---

# Style customization reference

Each location can override the SPA's look with `locations/<slug>/assets/styles.css`. On `captal locations push <slug>` the file is gzipped and uploaded to `s3://<bucket>/<slug>/custom-styles.css.gz`. The SPA loads it via `<link rel="stylesheet" href="./custom-styles.css.gz" onerror="this.remove()">` AFTER the base stylesheet, so any selector/variable you define overrides the default by CSS cascade order.

If the file is absent, `onerror="this.remove()"` swallows the 404 silently. You can ship a location without `styles.css` and the SPA will use defaults.

---

## Quick start: variables-only theming

Most rebrands need nothing more than this in your `styles.css`:

```css
:root {
  --color-primary: #ff6600;          /* buttons, focus, links */
  --color-primary-dark: #cc5200;     /* button hover */
  --color-primary-light: #ff8533;
  --bg-gradient-start: #ff6600;      /* welcome screen background */
  --bg-gradient-end: #ffaa55;
  --color-success: #22c55e;          /* submit/continue confirm color */
}
```

That re-skins the whole SPA. Keep customization at the variable level whenever possible — variables are stable across SPA updates; class selectors may be renamed.

---

## Screen-by-screen anatomy

Every screen sits inside the **Layout** wrapper. The layout owns the loading-state choreography, the brand pulse during i18n fetch, and the footer slot. Once content is loaded, individual screens populate `.view-content` and (some) `.layout-footer`.

### Layout wrapper (always present)

Source: `client/src/whitelabel/captal/client/views/Layout.scala`

```
.app-root
└── .app-layout
    ├── .layout-content
    │   └── .layout-view (.loading-state | .loaded-state)
    │       ├── .view-icon (.brand-pulse during loading)
    │       │   └── img.brand-icon            ← from assets/brand-icon.svg
    │       └── .view-content (.hidden | .visible)
    │           └── [SCREEN CONTENT]
    └── .layout-footer (.hidden | .visible)
        └── [SCREEN FOOTER]
```

State flow: `.loading-state` → `.loaded-state` once translations are fetched. `.view-content` and `.layout-footer` flip from `.hidden` to `.visible` with the staggered fade-in animations (`--animate-fade-in-1..5`).

The `.brand-pulse` modifier on `.view-icon` runs the loading pulse animation. Override its `@keyframes` if you want a different rhythm:
```css
.view-icon.brand-pulse .brand-icon { animation: my-pulse 1.5s ease-in-out infinite; }
@keyframes my-pulse { 0%,100% { transform: scale(1); } 50% { transform: scale(1.1); } }
```

### Welcome screen

Source: `client/src/whitelabel/captal/client/views/WelcomeView.scala`

The first screen the user sees once i18n is loaded. Full-bleed gradient background.

```
.view-content
└── .welcome-view
    ├── .welcome-title          (h1) — i18n key welcome.title
    ├── .welcome-subtitle       (p)  — i18n key welcome.subtitle
    ├── .welcome-steps
    │   └── .step ×3
    │       ├── (numeric badge / icon, styled by ::before or inline)
    │       └── .step-text       (span) — i18n keys welcome.steps.step1..3
    │       (inline animation-delay: 0.1s × index for stagger)
    └── .welcome-button          (button) — i18n key welcome.button.start / .connecting

.layout-footer
└── .locale-button               (select) — language picker
```

**Key variables for this screen:**
- `--bg-gradient-start`, `--bg-gradient-end`, `--bg-gradient-angle` — full-bleed background
- `--text-inverse` — text-on-gradient color (white by default)
- `--glass-bg`, `--glass-border`, `--glass-text` — locale picker styling (sits on the gradient)
- `--shadow-button-hover` — primary CTA hover shadow
- `--icon-size-lg` — brand icon size in `.view-icon`

**Common recipes:**
```css
/* Brand-colored gradient */
:root { --bg-gradient-start: #1e40af; --bg-gradient-end: #2563eb; }

/* Replace step bullets with custom badges */
.welcome-steps .step::before {
  content: counter(step) ".";
  counter-increment: step;
  font-weight: var(--font-weight-bold);
  color: var(--text-inverse);
}
.welcome-steps { counter-reset: step; }

/* Match brand button shape */
.welcome-button { border-radius: 4px; text-transform: uppercase; letter-spacing: 0.05em; }
```

### Identification Question screen

Source: `client/src/whitelabel/captal/client/views/IdentificationQuestionView.scala`

Used for email, profiling, and location surveys (categories defined in `shared/surveys/*.yaml`). The same template renders any question type via runtime branches.

```
.view-content
└── .question-view
    └── .question-container
        └── .questions-list
            └── .question-card (.card-error | .card-valid | pristine)
                ├── .question-title          (h2)
                ├── .question-description    (p, optional)
                ├── .question-input-area
                │   │
                │   ├── INPUT TYPE = input  (text / email)
                │   │   └── input.text-input[type=text|email]
                │   │
                │   ├── INPUT TYPE = radio
                │   │   └── .radio-group
                │   │       └── label.radio-option ×N
                │   │           ├── input[type=radio]
                │   │           └── span.radio-label
                │   │
                │   ├── INPUT TYPE = checkbox
                │   │   └── .checkbox-group
                │   │       └── label.checkbox-option ×N
                │   │           ├── input[type=checkbox]
                │   │           └── span.checkbox-label
                │   │
                │   ├── INPUT TYPE = dropdown
                │   │   └── select.select-input
                │   │
                │   ├── INPUT TYPE = numeric
                │   │   └── input.numeric-input[type=number]
                │   │
                │   ├── INPUT TYPE = date
                │   │   └── input.date-input[type=date]
                │   │
                │   └── INPUT TYPE = rating
                │       └── .rating-input  (placeholder — implementation pending)
                │
                └── .validation-error        (div, conditional — i18n: question.invalidEmail, etc.)

.layout-footer
├── .question-submit-button       (button) — i18n key question.submit / .next
└── .server-error                 (div, conditional, on API failure)
```

**State modifiers** (set at runtime on `.question-card`):
- pristine (no class) — initial state, before user touches input
- `.card-valid` — answer matches validation rules
- `.card-error` — validation rules failed

**Key variables:**
- `--surface-primary` — card background
- `--border-color`, `--border-color-hover` — input borders
- `--radius-md` — card and input border radius
- `--color-primary` — focus ring + active radio dot
- `--color-error`, `--surface-error` — error state colors
- `--shadow-focus` — focus ring around inputs
- `--control-size` (default `18px`) — radio/checkbox dot/box size
- `--font-size-base`, `--font-size-lg` — input text and title sizes

**Common recipes:**
```css
/* Tighter cards with brand accent border */
.question-card {
  border-left: 4px solid var(--color-primary);
  padding: var(--space-xl);
}

/* Pill-shaped radio options */
.radio-option {
  border-radius: var(--radius-full);
  padding: var(--space-sm) var(--space-md);
  background: var(--surface-secondary);
}
.radio-option:has(input:checked) {
  background: var(--color-primary);
  color: var(--text-inverse);
}

/* Error card highlight */
.question-card.card-error {
  border: 2px solid var(--color-error);
  background: var(--surface-error);
}
```

### Advertiser Video screen

Source: `client/src/whitelabel/captal/client/views/AdvertiserVideoView.scala`

Two-phase: intro card → playback. Supports fullscreen. Most visually dense screen.

```
.view-content
└── .video-hero (.fullscreen — when fullscreen API active)
    │
    ├── PHASE 1: .video-intro (initial 2–3s)
    │   └── .video-intro-card
    │       ├── .video-intro-icon
    │       │   └── img.brand-icon
    │       ├── .video-intro-title              (h1) — video.yaml's title
    │       ├── .video-intro-warning            (p)  — i18n key video.payAttention
    │       └── .video-intro-pulse              (div, animated bar)
    │
    └── PHASE 2: .video-hero-content (after intro)
        └── .video-hero-player (toggle .controls-visible on click in fullscreen)
            ├── .video-hero-video-wrapper
            │   └── video.video-hero-video
            └── .video-controls
                ├── button.video-control-btn ×2  (play/pause + fullscreen)
                │   └── svg.control-icon
                └── button.video-progress-btn (.active when complete)
                    ├── svg.progress-ring
                    │   ├── circle.progress-ring-bg
                    │   └── circle.progress-ring-progress  (--offset CSS var animated)
                    └── span.progress-checkmark
```

**Key variables:**
- `--surface-primary`, `--text-primary` — intro card colors
- `--shadow-lg` — intro card elevation
- `--radius-lg` — intro card corners
- `--color-primary` — progress ring stroke color
- Custom: `--offset` is set inline by JS to animate the progress arc

**Common recipes:**
```css
/* Cinematic dark video frame */
.video-hero { background: #000; }
.video-hero-player { background: #000; box-shadow: 0 0 60px rgba(0,0,0,0.6); }

/* Brand-colored progress ring */
.progress-ring-progress { stroke: var(--color-primary); }
.progress-ring-bg { stroke: rgba(255,255,255,0.2); }

/* Floating glass controls */
.video-controls {
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-full);
}
```

Empty state (no video matched the rotation): `.video-hero-empty` is rendered with `i18n.video.noVideoAvailable`. Style the same way as Welcome's text — same `--text-inverse` over gradient applies.

### Advertiser Video Survey screen

Source: `client/src/whitelabel/captal/client/views/AdvertiserVideoSurveyView.scala`

**Reuses the Identification Question DOM**: `.question-view`, `.question-card`, all input classes (`.text-input`, `.radio-group`, etc.), `.question-submit-button`. Whatever you style on those classes for identification questions also applies here.

If you want different visuals between identification and video surveys, target the parent context:
```css
/* Different background per question phase: hard, since both share .question-view.
   Easier alternative: rely on body or a phase indicator — there's no current
   phase-class hook. Best to keep them visually unified. */
```

### Ready screen

Source: `client/src/whitelabel/captal/client/views/ReadyView.scala`

Final "thank you" screen. Two text elements with staggered animation.

```
.view-content
└── .ready-view
    ├── .ready-title       (h1, animation-delay: 1000ms)  — i18n key ready.title
    └── .ready-subtitle    (p,  animation-delay: 1200ms)  — i18n key ready.subtitle

.layout-footer
└── .dev-reset-button       (button, ONLY when ENVIRONMENT=dev) — i18n key ready.resetButton
```

**Key variables:**
- `--font-size-2xl` — title
- `--text-inverse` — text color (sits over gradient)
- `--space-2xl`, `--space-3xl` — vertical rhythm

`.dev-reset-button` is invisible in production (the SPA is built with `ENVIRONMENT=prod`). No need to style it for customer rollouts.

### Initial HTML loader

Source: `client/index.html.template` (NOT a Scala.js view)

Shown for the first ~300ms before Scala.js boots. The class is `.initial-loader` and it fades out via `.fade-out`.

```html
<div class="initial-loader">
  <div class="loader-icon brand-pulse">
    <img src="brand-icon.svg" class="brand-icon" alt="Loading">
  </div>
</div>
```

If you want the loader background to match your gradient before JS even runs, override:
```css
.initial-loader { background: linear-gradient(135deg, #ff6600, #ffaa55); }
```

### Navigation overlay (between phases)

Source: `client/src/whitelabel/captal/client/Main.scala`

Shown briefly between phase transitions when `AppState.isNavigating` is true (e.g. clicking the welcome CTA before the next page mounts).

```
.app-root
└── .nav-loader
    └── .loader-icon.brand-pulse
        └── img.brand-icon
```

Same pulse animation as the layout's loading state.

---

## Shared classes summary

| Class                  | Where rendered                       | Type           | Purpose                                       |
|------------------------|--------------------------------------|----------------|-----------------------------------------------|
| `.brand-icon`          | Layout, Video, Main                  | image          | Logo (sourced from `assets/brand-icon.svg`)   |
| `.brand-pulse`         | Layout (loading), Main (nav)         | animation hook | Pulsing scale animation                       |
| `.text-input`          | Question, Video Survey               | input          | Text/email field                              |
| `.radio-group/.option` | Question, Video Survey               | input          | Single-select set                             |
| `.checkbox-group/.option` | Question, Video Survey            | input          | Multi-select set                              |
| `.select-input`        | Question, Video Survey               | input          | Dropdown                                      |
| `.numeric-input`       | Question, Video Survey               | input          | Number field                                  |
| `.date-input`          | Question, Video Survey               | input          | Date picker                                   |
| `.validation-error`    | Question, Video Survey               | message        | Inline validation message                     |
| `.server-error`        | Question, Video Survey               | message        | Footer API-error banner                       |
| `.layout-view`         | All                                  | container      | View slot inside `.layout-content`            |
| `.view-content`        | All                                  | container      | Inner content slot, fade-in animated          |
| `.layout-footer`       | All                                  | container      | Bottom slot for buttons/locale                |
| `.locale-button`       | Welcome footer                       | control        | Language selector                             |

## State modifier classes

Apply these as conditional classes (set by the SPA at runtime):

| Modifier              | Applied to              | Triggered when                                   |
|-----------------------|-------------------------|--------------------------------------------------|
| `.loading-state`      | `.layout-view`          | i18n bundle still fetching                       |
| `.loaded-state`       | `.layout-view`          | i18n loaded, content ready                       |
| `.hidden`             | `.view-content`, `.layout-footer` | during loading                       |
| `.visible`            | same                    | post-load (triggers stagger animation)           |
| `.fade-out`           | `.initial-loader`       | once Scala.js mounts (HTML-managed)              |
| `.brand-pulse`        | `.view-icon`, `.loader-icon` | continuous loader animation              |
| `.card-valid`         | `.question-card`        | input passes all validation rules                |
| `.card-error`         | `.question-card`        | input fails validation                           |
| `.fullscreen`         | `.video-hero`           | fullscreen API active                            |
| `.controls-visible`   | `.video-hero-player`    | tap-to-show controls in fullscreen               |
| `.active`             | `.video-progress-btn`   | video has been watched (duration met)            |

## Animations exposed as variables

| Variable                  | Default                                  | Used by                        |
|---------------------------|------------------------------------------|--------------------------------|
| `--animate-fade-in-1`     | `fadeInUp 0.5s ease-out 0.2s both`       | `.view-content > :nth-child(1)`|
| `--animate-fade-in-2..5`  | same with +0.1s stagger each             | nth-child 2..5                 |
| `--transition-fast`       | `0.15s ease`                             | hover transitions              |
| `--transition-base`       | `0.2s ease`                              | most state changes             |
| `--transition-slow`       | `0.3s ease`                              | layout-level transitions       |

The `@keyframes fadeInUp` is defined in the base stylesheet. Redefine it if you want different motion, or replace `--animate-fade-in-1..5` to point to your own keyframes.

---

## Full CSS variable reference

(Same as before — abbreviated here, full table in the previous version.)

#### Colors
`--color-primary`, `--color-primary-dark`, `--color-primary-light`, `--color-success`, `--color-error`, `--color-warning`, `--color-disabled`, `--color-success-disabled`

#### Background gradient
`--bg-gradient-start`, `--bg-gradient-end`, `--bg-gradient-angle`

#### Surfaces & text
`--surface-primary`, `--surface-secondary`, `--surface-error`, `--text-primary`, `--text-secondary`, `--text-inverse`, `--text-link`

#### Typography
`--font-family`, `--font-size-{xs,sm,base,lg,xl,2xl}`, `--font-weight-{normal,medium,semibold,bold}`, `--line-height`

#### Spacing
`--space-{xs,sm,md,lg,xl,2xl,3xl}`

#### Borders & radius
`--border-color`, `--border-color-hover`, `--radius-{sm,md,lg,full}`

#### Shadows
`--shadow-{sm,md,lg}`, `--shadow-button-hover`, `--shadow-success`, `--shadow-success-hover`, `--shadow-focus`

#### Glass overlays (welcome locale, video controls)
`--glass-bg`, `--glass-text`, `--glass-border`, `--glass-focus`

#### Sizing
`--icon-size-{lg,md}`, `--control-size`, `--content-max-width`, `--content-padding`

#### Transitions / animations
`--transition-{fast,base,slow}`, `--animate-fade-in-{1..5}`

For exact default values check `client/assets/styles.css:1-100` (`:root { ... }` block).

---

## Brand icon

`locations/<slug>/assets/brand-icon.svg` is uploaded to `s3://<bucket>/<slug>/brand-icon.svg`. The SPA references it as `brand-icon.svg` (relative — resolves under each location's slug via the `<base href>` injection). Used inside:
- `.view-icon` (loading + post-load layout — every screen)
- `.video-intro-icon` (advertiser video intro)
- `.loader-icon` (initial HTML loader + navigation overlay)

**Format**: must be SVG (`<svg>...</svg>`). PNG/JPG with `.svg` extension won't render.

**Sizing**: control via `--icon-size-lg` and `--icon-size-md` rather than the `<img>` directly. The icon stretches to fill its container.

**Tip — recolorable icons**: if your SVG uses `fill="currentColor"`, you can recolor it via CSS without re-uploading:
```css
.view-icon .brand-icon { color: var(--text-inverse); }  /* makes a currentColor icon white */
```

---

## Cascade rules and gotchas

- **Order**: base styles load first, custom-styles second. Same specificity → custom wins. A variable on `:root { --color-primary: ... }` in custom-styles overrides the base.
- **Specificity**: avoid `!important` unless absolutely necessary. The base stylesheet doesn't use it, so plain selectors work.
- **No CSS preprocessing**: the file is served verbatim (gzipped). No SCSS, no PostCSS.
- **Mobile-first**: the base stylesheet uses fluid units and mobile-first breakpoints. Test at 360px width minimum.
- **Don't restyle `.app-root` margins/padding**: the layout depends on them being controlled via `--space-*` and `--content-*` variables.
- **Don't break `.brand-pulse`**: at minimum keep some animation on `.view-icon.brand-pulse` so users see motion during the loading phase. If the i18n fetch is slow (>1s), a static icon looks frozen.

## Testing locally

1. `./mill clean client.generatedSources`
2. `ENVIRONMENT=dev ./mill client.bundle`
3. Copy `locations/<slug>/assets/styles.css` to `out/client/bundle.dest/custom-styles.css`.
4. `./mill api.dev --slug <slug> --dir locations/<slug> --shared-dir shared`.
5. Open `http://localhost:8080/<slug>/`.

For the deployed version, just run `captal locations push <slug>` (the file is gzipped + uploaded + CloudFront-invalidated automatically).

## Source of truth

`client/assets/styles.css` is the authoritative list of variables and class hooks. View source files (`client/src/whitelabel/captal/client/views/*.scala`) are authoritative for which class is rendered on which screen — grep for `cls := "..."` to find any class.
