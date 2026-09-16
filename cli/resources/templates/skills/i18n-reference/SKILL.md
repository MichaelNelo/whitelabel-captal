---
name: i18n-reference
description: How to build and audit translations for a location. The authoritative key list is served by the MCP server at i18n://required-keys, derived from the schema. Use when building or auditing translations. Triggers on "i18n keys", "translation keys", "required translations", "missing translations", "claves i18n", "traducciones requeridas".
version: 2.0.0
---

# i18n reference

## Where the key list lives

**Read `i18n://required-keys` from the backoffice MCP server.** It returns every key the portal
requires, in declaration order, each with a line describing what it is for and which
`{placeholders}` it must keep.

That resource is derived from the `I18n` case class hierarchy in
`core/src/whitelabel/captal/core/i18n/I18n.scala` — the key list from the type, the descriptions
from an `I18n` instance the compiler requires to be total. Neither can drift from what the portal
actually reads.

This skill used to enumerate the keys itself. It went stale: it was missing
`welcome.authorized.title` and `welcome.authorized.remaining` (added with the access-expiry
countdown), stated a total of 36 when there were 37 listed and 39 real, and claimed a missing key
makes the i18n endpoint fail to decode. That is why the list is no longer written here.

## What a missing key actually does

Nothing fails. The decoder substitutes the literal `[key]` — so `welcome.title` missing from
`es.yaml` renders `[welcome.title]` on screen, in Spanish, to a real guest. There is no error, no
log line, and no failed deploy. **Auditing coverage before deploying is the only defence.**

## YAML notes

- All keys are **flat** dotted strings — they look hierarchical but are NOT nested objects. Quote
  them: `"welcome.title": "..."`.
- The same set of keys must exist in every locale file. Differing key sets across `es.yaml` /
  `en.yaml` is a bug, not a feature.
- The locale code is the filename. Use ISO 639-1: `es.yaml`, `en.yaml`, `pt.yaml`, etc.
- After editing, deploy with `captal locations push <slug>`. The provision step content-hashes the
  file so unchanged YAML doesn't trigger a reapply.

## Adding a new locale

1. Decide the ISO 639-1 code (e.g. `pt`).
2. Copy an existing locale file: `cp locations/<slug>/i18n/en.yaml locations/<slug>/i18n/pt.yaml`.
3. Read `i18n://required-keys` and translate every value, keeping every key and every
   `{placeholder}` intact.
4. Add the locale to the SPA's locale picker (shared frontend config — coordinate with the project
   owner).
5. `captal locations push <slug>`.

## Source of truth

`core/src/whitelabel/captal/core/i18n/I18n.scala`. Adding a field there is a compile error until it
is given a description, and both the key list and the descriptions flow from it into
`i18n://required-keys` automatically. **This skill does not need updating when keys change.**
