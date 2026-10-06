---
name: style-reference
description: How to theme a location's portal through set_location_styles — the per-screen DOM structure, the class hooks, the state modifiers, and which variable drives which part. Call get_css_schema for the variable list itself; it is derived from the portal's stylesheet and this document is not. Use when customizing branding, colors, typography, layout, or specific screens (welcome, question, video, video-survey, ready). Triggers on "custom styles", "branding", "theme", "css variables", "override styles", "style screen", "estilos", "marca", "colores", "personalizar pantalla".
version: 2.1.0
---

# Style customization reference

Each location can override the SPA's look with its own stylesheet, written through the backoffice:

1. `get_css_schema` — every custom property the portal honours, with its default and what it
   controls, grouped as the stylesheet groups them. Read it before writing one: this document is
   the *how*, that tool is the *what*, and it is the only list of variables that cannot be stale.
2. `get_location_styles` — what is deployed today, or nothing when the location uses the base theme.
3. `set_location_styles` — replaces it. **The whole file, not a patch**: read first when editing,
   or the rest of the stylesheet is lost.

The write also invalidates the CDN for that one file, so the change is visible immediately. There is
no deploy step and nothing else to push.

The stylesheet is loaded AFTER the base one, so any selector or variable defined in it overrides
the default by cascade order. A location without one is normal — the link element carries
`onerror="this.remove()"`, so the missing file is swallowed and the base theme is used.

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

Every screen sits inside the **Layout** wrapper. The layout owns the loading-state choreography, the brand icon's waiting animation during the i18n fetch, and the footer slot. Once content is loaded, individual screens populate `.view-content` and (some) `.layout-footer`.

### Layout wrapper (always present)

Source: `client/src/whitelabel/captal/client/views/Layout.scala`

```
.app-root
└── .app-layout
    ├── .layout-content
    │   └── .layout-view (.loading-state | .loaded-state)
    │       ├── .view-icon
    │       │   └── .brand-ring
    │       │       └── img.brand-icon        ← from assets/brand-icon.svg
    │       └── .view-content (.hidden | .visible)
    │           └── [SCREEN CONTENT]
    └── .layout-footer (.hidden | .visible)
        └── [SCREEN FOOTER]
```

State flow: `.loading-state` → `.loaded-state` once translations are fetched. `.view-content` and `.layout-footer` flip from `.hidden` to `.visible` with the staggered fade-in animations (`--animate-fade-in-1..5`).

`.view-icon` carries no per-state class of its own: the waiting animation is driven by
`.loading-state` / `.loaded-state` on the parent `.layout-view`, so the icon is one element across
the whole transition. While waiting, `img.brand-icon` runs `@keyframes breathe` (opacity, and
symmetric — `0%` and `100%` are the same state, which is what lets two icons that mount out of step
converge without synchronisation). On `.loaded-state` the breathing stops and `brandGlowFlash`
plays once. Override either keyframe for a different rhythm:
```css
.brand-icon { animation: my-wait 1.5s ease-in-out infinite; }
.layout-view.loaded-state .brand-icon { animation: none; }
@keyframes my-wait { 0%,100% { transform: scale(1); } 50% { transform: scale(1.1); } }
```

### Welcome screen

Source: `client/src/whitelabel/captal/client/views/WelcomeView.scala`

The first screen the user sees once i18n is loaded. Full-bleed gradient background.

```
.view-content
└── .welcome-view
    ├── .welcome-title          (h1) — i18n key welcome.title
    ├── .welcome-subtitle       (p)  — i18n key welcome.subtitle
    └── .welcome-steps
        └── .step ×3
            ├── (numeric badge / icon, styled by ::before or inline)
            └── .step-text       (span) — i18n keys welcome.steps.step1..3
            (inline animation-delay: 0.1s × index for stagger)

.layout-footer
├── .locale-button               (select) — language picker
└── .welcome-button              (button) — i18n key welcome.button.start / .connecting
```

**Authorized variant.** Once a guest already has access, the same route renders a second
`.welcome-view` with the expiry countdown instead of the steps and the CTA:

```
.view-content
└── .welcome-view
    ├── .welcome-title                  (h1) — i18n key welcome.authorized.title
    └── .welcome-countdown-wrapper
        └── .welcome-countdown-card
            ├── .welcome-countdown      (p) — the formatted time remaining
            └── .welcome-countdown-label (p) — i18n key welcome.authorized.remaining

.layout-footer                           — empty in this state: no locale picker, no CTA
```

The switch is driven by `Phase.Authorized` on the app state, not by a class on `.welcome-view`, so
there is no selector that distinguishes the two variants. Target the children that only one of them
has (`.welcome-steps` vs `.welcome-countdown-wrapper`).

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

Used for the three deployment-wide identification surveys — email, profiling and location. They are
rows in the database, created with `create_identification_survey` and listed by `list_surveys`; they
are picked by category with no location filter, so they are not per-location content. The same
template renders any question type via runtime branches.

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

Two layers, not two phases: the player mounts first and buffers underneath while the intro covers
it, then the intro fades out. The two are **siblings**, and the intro is the overlay.

```
[view root]
├── .video-hero (.fullscreen — when fullscreen API active)
│   └── .video-hero-content
│       └── .video-hero-player (toggle .controls-visible on click in fullscreen)
│           ├── video.video-hero-video
│           └── .video-controls
│               ├── button.video-control-btn ×2  (play/pause + fullscreen)
│               │   └── svg.control-icon
│               └── button.video-progress-btn (.active when complete)
│                   ├── svg.progress-ring
│                   │   ├── circle.progress-ring-bg
│                   │   └── circle.progress-ring-progress  (--offset CSS var animated)
│                   └── span.progress-checkmark
│
└── .video-intro (.leaving while it dissolves; unmounts after)
    └── .app-layout  ← a nested Layout, same brand icon and stagger as every other screen
        └── .view-content
            ├── .video-intro-title            (h1) — the video's stored title, per locale
            ├── .video-intro-warning          (p)  — i18n key video.payAttention
            └── .video-intro-pulse            (div, indeterminate sweep bar)
```

A video's title is a localized string set when the video is registered (`create_advertiser_video`
or `create_promo_video`) and falls back to the literal `Video` when none was stored for the active
locale. It is content, not styling — nothing in a stylesheet changes it.

`.video-intro` must stay opaque and above the player. Both it and `.video-hero` are
`position: fixed; inset: 0`; the intro wins on `z-index: 10` and paints the same gradient as a
ground. Give it a transparent background and the video plays visibly straight through the intro.

**Key variables:**
- `--bg-gradient-start`, `--bg-gradient-end`, `--bg-gradient-angle` — both layers paint this
  gradient; the intro needs an opaque one to cover the player
- `--text-inverse` — intro text, which sits on that gradient
- `--video-intro-out` — how long the intro takes to dissolve. Must match
  `AdvertiserVideoView.IntroFadeMs`, which decides when the element is removed: shorter and the
  fade is cut off, longer and the overlay lingers invisibly.
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

Final "thank you" screen, shown while `/api/finish` is in flight. The spinner stays up for as long
as the view is alive; on success the router unmounts it and renders the authorized Welcome.

```
.view-content
└── .ready-view
    ├── .ready-title              (h1, animation-delay: 1000ms)  — i18n key ready.title
    └── .ready-loader-wrapper
        ├── .spinner.spinner--large
        └── .ready-subtitle       (p, animation-delay: 1200ms)   — i18n key ready.subtitle

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

The only thing on screen while the bundle and the first view's chunk come down. It is **removed by
Layout on mount**, not dismissed by a timer and not faded by a class the SPA sets — so there is no
exit state to style. Its markup deliberately mirrors the Layout loading state, so the handoff moves
nothing.

```html
<div class="initial-loader">
  <div class="brand-ring">
    <span class="ring-track"></span>
    <span class="ring-arc"></span>
    <img src="./brand-icon.svg" class="brand-icon" alt="Cargando">
  </div>
</div>
```

`.ring-track` and `.ring-arc` are `display: none` in the base stylesheet: the waiting animation is
currently the icon breathing, not a ring. They are still in the markup, so a stylesheet that wants
a ring back has those two hooks and the `--loader-ring-*` variables to do it with.

If you want the loader background to match your gradient before JS even runs, override:
```css
.initial-loader { background: linear-gradient(135deg, #ff6600, #ffaa55); }
```

### Navigation overlay (between phases)

Source: `client/src/whitelabel/captal/client/Main.scala`

Shown briefly between phase transitions when `AppState.isNavigating` is true (e.g. clicking the welcome CTA before the next page mounts).

```
.app-root
└── .nav-loader (.is-visible while navigating)
    └── .brand-ring
        └── img.brand-icon
```

Always in the DOM; `.is-visible` is what flips opacity and visibility. Its background repeats the
body gradient so that while visible it opaquely covers the outgoing view — keep it opaque or the
old screen bleeds through mid-transition. Same `breathe` animation on the icon as the layout's
loading state.

---

## Shared classes summary

| Class                  | Where rendered                       | Type           | Purpose                                       |
|------------------------|--------------------------------------|----------------|-----------------------------------------------|
| `.brand-icon`          | Layout, Video intro, Main, index.html | image         | Logo (sourced from `assets/brand-icon.svg`)   |
| `.brand-ring`          | Layout, Main (nav), index.html       | container      | Wraps the icon; ring hooks live inside it     |
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
| `.is-visible`         | `.nav-loader`           | a navigation is in flight                        |
| `.card-valid`         | `.question-card`        | input passes all validation rules                |
| `.card-error`         | `.question-card`        | input fails validation                           |
| `.fullscreen`         | `.video-hero`           | fullscreen API active                            |
| `.controls-visible`   | `.video-hero-player`    | tap-to-show controls in fullscreen               |
| `.leaving`            | `.video-intro`          | intro dissolving into the player                 |
| `.active`             | `.video-progress-btn`   | video has been watched (duration met)            |

The `.initial-loader` has no exit modifier: Layout removes the element on mount.

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

## CSS variable groups

**This list is orientation, not the reference. `get_css_schema` is.** That tool reads the portal's
own `client/assets/styles.css` — the file `mcp.resources` copies into the server's jar — so it
answers with the variables that exist today, their real defaults and what each one controls. This
document is prose maintained by hand and has already fallen behind the stylesheet once; treat it as
a map of the territory and the tool as the inventory. Never hand an operator a variable name from
here without having seen it in `get_css_schema`.

The groups below are the same ones the schema groups by, which is what makes the two easy to read
side by side.

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

#### Glass / overlay colors (welcome locale, video controls, states over the gradient)
`--glass-bg`, `--glass-text`, `--glass-border`, `--glass-focus`, `--error-bg-transparent`, `--error-text-light`, `--success-bg-transparent`

#### Sizing
`--icon-size-{lg,md}`, `--control-size`, `--content-max-width`, `--content-padding`, `--loader-ring-{gap,width,out}`, `--loader-settle`, `--loader-handoff`, `--video-intro-out`

#### Transitions / animations
`--transition-{fast,base,slow}`, `--animate-fade-in-{1..5}`

For the exact defaults, call `get_css_schema` — it carries the declared value of every variable.
The declarations themselves are the `:root { ... }` block at the top of
`client/assets/styles.css`.

---

## Brand icon

An SVG, written with `get_location_icon` and `set_location_icon` — the whole document, not a patch.
Scripts and `on*` handlers are refused.

Rendered through an `img.brand-icon` inside a `.brand-ring`, at small sizes, in three places:
- `.view-icon` — the Layout icon, so every screen including the advertiser video intro
- `.nav-loader` — the navigation overlay between phases
- `.initial-loader` — the static loader in `index.html`, before the bundle arrives

Because all three use the same two classes, a rule on `.brand-icon` alone reaches every one of
them. Scope it under `.view-icon` / `.nav-loader` / `.initial-loader` when you want them to differ.

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
- **Don't leave the brand icon static while loading**: keep some animation on `.brand-icon` during
  `.loading-state`, or override `@keyframes breathe`. If the i18n fetch is slow (>1s), a frozen
  icon reads as a hung page.

## Testing locally

1. `./mill clean client.generatedSources`
2. `ENVIRONMENT=dev ./mill client.bundle`
3. `./mill api.dev --slug <slug>` — the whole dev workflow: rqlite, migrations, client, server. It
   takes no provisioning directory; the server reads whatever the backoffice wrote to the database.
4. Open `http://localhost:8080/<slug>/`.

What the dev server shows is the **base** theme. It serves `client/assets/styles.css` at
`/assets/styles.css` and nothing at `/<slug>/custom-styles.css.gz`, which is where the location's
override lives in production — so a local run never picks one up. To eyeball a stylesheet before
publishing it, paste the rules into `client/assets/styles.css` temporarily and revert afterwards;
they are not a location's content and must not be committed.

Once it looks right, `set_location_styles` publishes it. There is no deploy step.

## Source of truth

`get_css_schema` is the authoritative list of variables, derived from `client/assets/styles.css`
itself — `mcp.resources` copies that file into the server's jar so the two cannot disagree. The
stylesheet is also the authority for class hooks, which nothing derives yet; this document is a
hand-maintained reading of it. View source files
(`client/src/whitelabel/captal/client/views/*.scala`) are authoritative for which class is rendered
on which screen — grep for `cls := "..."` to find any class.

What a location currently has is whatever `get_location_styles` returns. A `styles.css` left over
in an operator's workspace is not authoritative and may predate edits made through the backoffice.
