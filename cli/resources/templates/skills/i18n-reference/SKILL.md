---
name: i18n-reference
description: Complete reference of the i18n keys the SPA expects. Lists every required key per section (welcome, ready, loading, error, question, video) with placeholders. Use when building or auditing translations. Triggers on "i18n keys", "translation keys", "required translations", "missing translations", "claves i18n", "traducciones requeridas".
version: 1.0.0
---

# i18n keys reference

The SPA expects a strict, fully-populated tree of keys. The schema is defined in `core/src/whitelabel/captal/core/i18n/I18n.scala` and decoded with `babel`. **Every key listed below is required** — a missing key causes the API's i18n endpoint to fail decoding and the SPA falls back to the default bundle.

## Required keys per locale file

Each `locations/<slug>/i18n/<locale>.yaml` MUST contain:

```yaml
# ── welcome screen ───────────────────────────────────────────
welcome.title: "..."                # large heading on landing
welcome.subtitle: "..."             # subline under the title
welcome.steps.step1: "..."          # bullet 1 of the 3-step explainer
welcome.steps.step2: "..."          # bullet 2
welcome.steps.step3: "..."          # bullet 3
welcome.button.start: "..."         # CTA to begin (idle state)
welcome.button.connecting: "..."    # CTA while session is being created
welcome.selectLanguage: "..."       # label of the locale picker

# ── loading state ────────────────────────────────────────────
loading.message: "..."              # generic spinner caption

# ── error state ──────────────────────────────────────────────
error.title: "..."                  # error page heading
error.retry: "..."                  # retry button label
error.generic: "..."                # fallback error message body

# ── question component (identification + per-video) ──────────
question.submit: "..."              # submit button on the last question
question.next: "..."                # next button on intermediate questions
question.required: "..."            # validation: field is required
question.invalidEmail: "..."        # validation: email format
question.invalidUrl: "..."          # validation: URL format
question.invalidPattern: "..."      # validation: regex pattern failed
question.minLength: "..."           # validation: too short. Use {min}
question.maxLength: "..."           # validation: too long. Use {max}
question.minSelections: "..."       # validation: not enough options. Use {min}
question.maxSelections: "..."       # validation: too many options. Use {max}
question.invalidOption: "..."       # validation: option not in the allowed set
question.ratingOutOfRange: "..."    # validation: rating out of [min,max]. Use {min} {max}
question.numericOutOfRange: "..."   # validation: number out of [min,max]. Use {min} {max}
question.dateOutOfRange: "..."      # validation: date out of [min,max]. Use {min} {max}
question.invalidAnswer: "..."       # validation: catch-all

# ── ready screen (post-flow) ─────────────────────────────────
ready.title: "..."                  # "thank you" heading
ready.subtitle: "..."               # subline (e.g. "you can now browse")
ready.resetButton: "..."            # only visible when ENVIRONMENT=dev

# ── video player ─────────────────────────────────────────────
video.pageTitle: "..."              # video screen heading
video.continueIn: "..."             # countdown to continue. Use {seconds}
video.watchComplete: "..."          # banner shown when video ended
video.markWatched: "..."            # button to confirm watching (manual)
video.loading: "..."                # caption while video buffers
video.noVideoAvailable: "..."       # message when no video matches the rotation
video.payAttention: "..."           # warning shown above the player intro
```

Total: **36 keys**.

## Placeholders

These keys interpolate runtime values via `{name}` placeholders — keep them intact when translating:

| Key                              | Placeholders            |
|----------------------------------|-------------------------|
| `question.minLength`             | `{min}`                 |
| `question.maxLength`             | `{max}`                 |
| `question.minSelections`         | `{min}`                 |
| `question.maxSelections`         | `{max}`                 |
| `question.ratingOutOfRange`      | `{min}`, `{max}`        |
| `question.numericOutOfRange`     | `{min}`, `{max}`        |
| `question.dateOutOfRange`        | `{min}`, `{max}`        |
| `video.continueIn`               | `{seconds}`             |

## Spanish (es) baseline

Copy this as `locations/<slug>/i18n/es.yaml` and rephrase for the location's tone:

```yaml
welcome.title: "Bienvenido a Captal"
welcome.subtitle: "Gana dinero respondiendo encuestas y viendo anuncios"
welcome.steps.step1: "Responde encuestas cortas"
welcome.steps.step2: "Mira anuncios de marcas"
welcome.steps.step3: "Acumula puntos y canjea premios"
welcome.button.start: "Comenzar"
welcome.button.connecting: "Conectando..."
welcome.selectLanguage: "Selecciona tu idioma"
loading.message: "Cargando..."
error.title: "Ha ocurrido un error"
error.retry: "Reintentar"
error.generic: "Algo salió mal. Por favor, intenta de nuevo."
question.submit: "Enviar"
question.next: "Siguiente"
question.required: "Este campo es requerido"
question.invalidEmail: "Ingresa un correo válido"
question.invalidUrl: "Ingresa una URL válida"
question.invalidPattern: "El formato no es válido"
question.minLength: "Mínimo {min} caracteres"
question.maxLength: "Máximo {max} caracteres"
question.minSelections: "Selecciona al menos {min} opciones"
question.maxSelections: "Selecciona máximo {max} opciones"
question.invalidOption: "Opción no válida"
question.ratingOutOfRange: "La calificación debe estar entre {min} y {max}"
question.numericOutOfRange: "El valor debe estar entre {min} y {max}"
question.dateOutOfRange: "La fecha debe estar entre {min} y {max}"
question.invalidAnswer: "Respuesta no válida"
ready.title: "¡Gracias!"
ready.subtitle: "Ya puedes navegar"
ready.resetButton: "Reiniciar (Dev)"
video.pageTitle: "Mira este video"
video.continueIn: "Continuar en {seconds}s"
video.watchComplete: "¡Video completado!"
video.markWatched: "Marcar como visto"
video.loading: "Cargando video..."
video.noVideoAvailable: "No hay videos disponibles"
video.payAttention: "Presta atención al video"
```

## English (en) baseline

```yaml
welcome.title: "Welcome to Captal"
welcome.subtitle: "Earn money by answering surveys and watching ads"
welcome.steps.step1: "Answer short surveys"
welcome.steps.step2: "Watch brand advertisements"
welcome.steps.step3: "Accumulate points and redeem rewards"
welcome.button.start: "Get Started"
welcome.button.connecting: "Connecting..."
welcome.selectLanguage: "Select your language"
loading.message: "Loading..."
error.title: "An error occurred"
error.retry: "Retry"
error.generic: "Something went wrong. Please try again."
question.submit: "Submit"
question.next: "Next"
question.required: "This field is required"
question.invalidEmail: "Please enter a valid email"
question.invalidUrl: "Please enter a valid URL"
question.invalidPattern: "The format is not valid"
question.minLength: "Minimum {min} characters"
question.maxLength: "Maximum {max} characters"
question.minSelections: "Select at least {min} options"
question.maxSelections: "Select at most {max} options"
question.invalidOption: "Invalid option"
question.ratingOutOfRange: "Rating must be between {min} and {max}"
question.numericOutOfRange: "Value must be between {min} and {max}"
question.dateOutOfRange: "Date must be between {min} and {max}"
question.invalidAnswer: "Invalid answer"
ready.title: "Thank you!"
ready.subtitle: "You can now browse"
ready.resetButton: "Reset (Dev)"
video.pageTitle: "Watch this video"
video.continueIn: "Continue in {seconds}s"
video.watchComplete: "Video complete!"
video.markWatched: "Mark as watched"
video.loading: "Loading video..."
video.noVideoAvailable: "No videos available"
video.payAttention: "Pay attention to the video"
```

## YAML notes

- All keys are **flat** dotted strings — they look hierarchical but are NOT nested objects. Quote them: `"welcome.title": "..."`.
- Same set of keys must exist in every locale file. Differing key sets across `es.yaml` / `en.yaml` is a bug, not a feature.
- The locale code is the filename. Use ISO 639-1: `es.yaml`, `en.yaml`, `pt.yaml`, etc.
- After editing, deploy with `captal locations push <slug>`. The provision step content-hashes the file so unchanged YAML doesn't trigger a reapply.

## Adding a new locale

1. Decide ISO 639-1 code (e.g. `pt`).
2. Copy an existing locale file: `cp locations/<slug>/i18n/en.yaml locations/<slug>/i18n/pt.yaml`.
3. Translate every value while keeping every key (and every `{placeholder}`).
4. Add the locale to the SPA's locale picker (this is shared frontend config — coordinate with the project owner).
5. `captal locations push <slug>`.

## Source of truth

The Scala case class hierarchy in `core/src/whitelabel/captal/core/i18n/I18n.scala` is authoritative. If a future change adds, removes, or renames a key, that file changes first and this skill needs an update.
