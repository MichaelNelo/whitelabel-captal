---
name: edit-i18n
description: Use this skill when adding or changing a location's portal translations through the backoffice MCP server. Triggers on "edit translations", "translate portal", "add locale", "missing translations", "traducciones".
version: 2.0.0
---

# Edit translations

Translations are written with tools, not files. This covers the portal's own interface text. The
wording of survey questions and their options is a different category — see
`skill://add-survey/SKILL.md`.

## Order of operations

1. `get_i18n_status` — per locale, which required keys are missing and which stored keys are no longer
   part of the schema.
2. Read `i18n://required-keys` for the full list, each key with what it is for and which
   `{placeholders}` it must keep.
3. `set_translations` — one call carries many entries for one locale. Keys outside the schema are
   refused as a batch rather than partly applied, because a mistyped key would be stored and never
   rendered.
4. `get_i18n_status` again. The write reports what is still missing, so this is mostly for a final look
   across every locale.

## What a missing key does

Nothing fails. The decoder substitutes the literal `[key]`, so `welcome.title` missing from Spanish
renders `[welcome.title]` on screen to a real guest. No error, no log line, no failed deploy.
Auditing before deploying is the only defence, which is what `get_i18n_status` is for.

## Placeholders

Some keys interpolate runtime values — `{seconds}`, `{min}`, `{max}`. `i18n://required-keys` marks
which ones. A translation that drops the token renders it literally to the guest.

## Retiring a key

`remove_translation` retires one key for one locale. The portal then shows `[key]` for that locale
until it is set again, so this is rarely what you want on its own — it is there for cleaning up a
key that no longer exists in the schema, which `get_i18n_status` reports as unknown.

Writing a translation again revives the retired row rather than creating a second one.

## Adding a locale

1. Pick the ISO 639-1 code.
2. `set_translations` for that locale with every key from `i18n://required-keys`.
3. Add the locale to the SPA's locale picker — shared frontend config, coordinate with the project
   owner.

## Related

- `skill://i18n-reference/SKILL.md` — where the key list comes from and why it is derived
