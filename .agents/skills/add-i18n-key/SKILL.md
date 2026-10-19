---
name: add-i18n-key
description: Use when adding a new translation key to the typed i18n schema. Covers the three places the key must be touched (I18n schema, I18n.descriptions, the view) and how an operator populates it afterward. Triggers on "add i18n key", "new translation", "agregar clave traduccion".
version: 2.0.0
---

# Add an i18n translation key

## Mental model

i18n keys are **typed end-to-end**, and derived rather than hand-mapped at every layer:

1. **Schema** (`core/src/whitelabel/captal/core/i18n/I18n.scala`) — a case class hierarchy defining
   the structure (e.g. `welcome.title`, `question.invalidEmail`). Cross-compiled JVM+JS. `I18n.schema`
   derives the flat dotted key set and the decode/encode functions from this type through
   `I18nSchema` — there is nothing elsewhere that repeats the key list by hand.
2. **Materialization** — `platform/src/whitelabel/captal/platform/services/LocaleService.scala`'s
   `LocaleServiceQuill.getI18n(locale)` reads the location's `localized_texts` rows and calls
   `I18nSchema.decode[I18n](messages)`. This is automatic: adding a field to `I18n` is picked up by
   `decode` without touching `LocaleService`. A key the rows don't cover decodes to the literal
   `[your.key]` rather than failing.
3. **Persistence** — the `localized_texts` table, scoped by `(locale, location_id, category =
   "frontend")`. There is no YAML and no file-based scaffold any more; every row is written by the
   MCP backoffice's `set_translations` tool, and read back with `get_i18n_status`.

If you skip the schema step or forget `I18n.descriptions`, the build doesn't compile — `I18n` is
total by construction. If an operator never writes the new key for a location, that location's
guests see `[your.key]` literally; nothing fails louder than that.

## Steps

### 1. Pick the parent group

i18n keys live in a sub-case-class of `I18n`. Current groups: `welcome.*`, `loading.*`, `error.*`,
`question.*`, `video.*`, `ready.*`.

Add to an existing group when the key belongs there. Create a new group only for a new screen (e.g.
`bonusGame`).

### 2. Update the I18n schema

`core/src/whitelabel/captal/core/i18n/I18n.scala`:

**Adding a field to an existing group:**

```scala
object Welcome:
  final case class Steps(step1: String, step2: String, step3: String, step4: String)  // step4 NEW
  ...
```

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

The `Decoder`/`Encoder` here are babel's (`io.taig.babel`), not circe — that's what the rest of
`I18n.scala` already uses for every nested group, and what `I18nSchema` derives against.

### 3. Describe the new key in `I18n.descriptions`

`I18n.scala`'s `descriptions` value is an `I18n` whose leaves hold a description instead of a
translation, and it's total by construction: the file **will not compile** until the new field is
described here.

```scala
bonusGame = BonusGame(
  title = "Heading of the bonus-game screen.",
  submit = "Submit button label.",
  score = "Current score. Use {score}."
)
```

This description is what `get_i18n_schema` and the `i18n://required-keys` resource answer with —
there is nothing else to update for an operator to discover the key exists. Document any
`{placeholder}` here; it's the only place that convention is written down.

### 4. That's it for materialization

Nothing to change in `LocaleService` — `LocaleServiceQuill.getI18n` already decodes whatever shape
`I18n` has through `I18nSchema.decode`. There is no bundled YAML default and no operator-facing
skill to update by hand either: the MCP backoffice's `i18n-reference` skill (under
`mcp/resources/skills/`) is explicit that it "does not need updating when keys change," because its
entire content is "call `get_i18n_schema`," which reads this same type.

### 5. Use in a view

```scala
child.text <-- I18nClient.i18n.map(_.bonusGame.title)
```

For placeholder interpolation, do the substitution at the call site:

```scala
child.text <-- I18nClient.i18n.combineWith(score.signal).map: (i18n, s) =>
  i18n.bonusGame.score.replace("{score}", s.toString)
```

### 6. Bump the API version if the change is a breaking one

Adding a field to `I18n` changes what `localized_texts` has to contain before `getI18n` returns a
fully-populated bundle — but it is not, on its own, a wire-format break: a missing key already
decodes to `[key]` rather than failing. A genuine break (removing a field, changing a type) follows
skill `bump-api-version`.

## Operator workflow after a new key ships

Once the API is deployed with the new schema, an operator (or the model acting for one) populates
it per location through the MCP backoffice:

1. `get_i18n_schema` — see the new key and its description.
2. `set_translations` — write it for every locale that location serves. The call is rejected whole
   if any key in the batch is unknown to the schema, so a typo never gets silently stored.
3. `get_i18n_status` — confirm nothing required is still missing.

See the MCP skills `edit-i18n` and `i18n-reference` for the full tool-level procedure. Until the
operator writes the key, the location serves `[bonusGame.title]` literal for it.

## What NOT to add to the schema

The schema is for **strings the SPA renders** that vary per location. Don't add:

- Long-form content (terms, marketing copy) — use a CMS or a separate API.
- Per-location data that's not translation (advertiser names, prices) — those live in the entity rows.
- Format strings with complex grammar (gender, plurals) — current schema doesn't support ICU MessageFormat. If you need it, that's a separate refactor.

## Anti-patterns

- ❌ Adding a field to `I18n.scala` but forgetting `I18n.descriptions` — the module will not
  compile, which is the point: there's no way to ship a key with no documented purpose.
- ❌ Hunting for a `LocaleService.buildI18n`-style mapping function to update — it doesn't exist.
  `I18nSchema.decode` is fully automatic; don't add one.
- ❌ Looking for a bundled YAML default or an operator-facing skill file to edit — content is
  written through MCP tools now, and `get_i18n_schema` already derives its answer from `I18n.scala`.
- ❌ Using i18n keys for one-off labels that won't change — overkill. Hardcode in the view if it's truly invariant (e.g. "Captal" brand name).
- ❌ Renaming a key without a deprecation period — a location whose operator hasn't re-run `set_translations` keeps serving `[old.key]` under the new name.
