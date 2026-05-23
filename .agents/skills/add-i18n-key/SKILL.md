---
name: add-i18n-key
description: Use when adding a new translation key to the typed i18n schema. Covers all 5 places the key must be touched (I18n schema, LocaleService, bundled defaults, operator skill, view). Triggers on "add i18n key", "new translation", "agregar clave traduccion".
version: 1.0.0
---

# Add an i18n translation key

## Mental model

i18n keys are **typed end-to-end**:

1. **Schema** (`core/.../i18n/I18n.scala`) — case class hierarchy defining the structure (e.g. `welcome.title`, `question.invalidEmail`). Cross-compiled JVM+JS.
2. **Materialization** (`infra/.../services/LocaleService.scala`) — `buildI18n(messages: Map[String, String]): I18n` maps flat YAML keys to the typed structure with `[key]` fallback.
3. **Persistence** (`localized_texts` table) — key/value pairs scoped by `(locale, location_id, category="frontend")`. Operators populate via `locations/<slug>/i18n/<locale>.yaml`.
4. **Defaults** (`cli/resources/templates/location/i18n/{es,en}.yaml`) — what `captal locations add <slug>` scaffolds.
5. **Operator doc** (`cli/resources/templates/skills/i18n-reference/SKILL.md`) — the 36-key reference.

If you skip ANY of these, the key fails silently (returns `[your.key]` literal in the SPA).

## Steps

### 1. Pick the parent group

i18n keys live in a sub-case-class of `I18n`. Current groups:

- `welcome.*` — landing page
- `loading.*` — generic spinner
- `error.*` — central error page
- `question.*` — survey form validation + buttons
- `video.*` — video player
- `ready.*` — final screen

Add to an existing group when the key belongs there. Create a new group only for a new screen (e.g. `bonusGame`).

### 2. Update the I18n schema

`core/src/whitelabel/captal/core/i18n/I18n.scala`:

**Adding a field to an existing group:**

```scala
object Welcome:
  final case class Steps(step1: String, step2: String, step3: String, step4: String)  // step4 NEW
  ...
```

Note: any field change re-derives the `babel` Decoder/Encoder, so the API now requires the new field in incoming YAML — fail-fast.

**Adding a new group:**

```scala
final case class BonusGame(title: String, submit: String, score: String)
object BonusGame:
  given Decoder[BonusGame] = deriveDecoder[BonusGame]
  given Encoder[BonusGame] = deriveEncoder[BonusGame]

// And add to I18n's case class:
final case class I18n(
    welcome: Welcome,
    ...
    bonusGame: BonusGame)   // NEW
```

### 3. Update LocaleService.buildI18n

`infra/src/whitelabel/captal/infra/services/LocaleService.scala`:

```scala
// Inside buildI18n:
bonusGame = I18n.BonusGame(
  title = get("bonusGame.title"),
  submit = get("bonusGame.submit"),
  score = get("bonusGame.score"))
```

`get(key)` falls back to `[key]` literal if the row is missing — useful for debugging which key wasn't provisioned.

### 4. Update the bundled defaults

`cli/resources/templates/location/i18n/es.yaml` and `.../en.yaml`:

```yaml
# es.yaml
bonusGame.title: "Juego Bonus"
bonusGame.submit: "Enviar"
bonusGame.score: "Puntaje: {score}"

# en.yaml
bonusGame.title: "Bonus Game"
bonusGame.submit: "Submit"
bonusGame.score: "Score: {score}"
```

These are the baseline that `captal locations add <slug>` scaffolds. Operators can override per-location.

**Placeholder convention**: `{name}` for runtime interpolation (e.g. `{min}`, `{max}`, `{seconds}`). Document any new placeholders.

### 5. Update the i18n-reference skill

`cli/resources/templates/skills/i18n-reference/SKILL.md`:
- Add the new keys to the "Required keys per locale file" code block.
- If any has placeholders, add to the placeholders table.
- Update the `es` / `en` baseline snippets with the new lines.
- Bump version in frontmatter (`version: 1.x.0` → `1.(x+1).0`).

### 6. Use in a view

```scala
child.text <-- I18nClient.i18n.map(_.bonusGame.title)
```

For placeholder interpolation, do the substitution at the call site:

```scala
child.text <-- I18nClient.i18n.combineWith(score.signal).map: (i18n, s) =>
  i18n.bonusGame.score.replace("{score}", s.toString)
```

### 7. Bump versions if necessary

- **API**: agregar una key NUEVA al schema requiere bumpear el API (porque el decoder cambia). Skill: `bump-api-version`.
- **CLI**: republicar la CLI con los templates actualizados así `captal locations add <slug>` scaffold-ea las keys nuevas. Skill: `bump-cli-version`.

## What NOT to add to the schema

The schema is for **strings the SPA renders** that vary per location. Don't add:

- Long-form content (terms, marketing copy) — use a CMS or a separate API.
- Per-location data that's not translation (advertiser names, prices) — those live in the entity rows.
- Format strings with complex grammar (gender, plurals) — current schema doesn't support ICU MessageFormat. If you need it, that's a separate refactor.

## Operator workflow after a new key ships

Operator's flow once the new schema is deployed:

```bash
captal update                  # gets new CLI 1.x.y
captal skills update           # overwrites .agents/skills/ with new i18n-reference
# Edit locations/<slug>/i18n/{es,en}.yaml to add the new keys (using i18n-reference for the list)
captal locations push <slug>   # provisions new rows; restart pulls them
```

Until the operator edits + pushes, their location serves `[bonusGame.title]` literal for that key (`get` fallback).

## Anti-patterns

- ❌ Adding a key to YAML defaults but forgetting `I18n.scala` — the bundled default has no effect; `buildI18n` won't read it.
- ❌ Adding to `I18n.scala` but forgetting `LocaleService.buildI18n` — compile fails or worse, returns default-init String "" for that field at runtime.
- ❌ Adding to `LocaleService.buildI18n` but forgetting to update operator-facing skill — operators don't know the key exists, deploy returns `[bonusGame.title]` everywhere.
- ❌ Using i18n keys for one-off labels that won't change — overkill. Hardcode in the view if it's truly invariant (e.g. "Captal" brand name).
- ❌ Renaming a key without a deprecation period — older API instances serving the old name break.
