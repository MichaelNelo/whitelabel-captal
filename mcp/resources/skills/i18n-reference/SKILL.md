---
name: i18n-reference
description: Where the portal's translation keys come from and how to audit coverage for a location. The key list is served by get_i18n_schema, derived from the I18n type. Use when building or auditing translations. Triggers on "i18n keys", "translation keys", "required translations", "missing translations", "claves i18n", "traducciones requeridas".
version: 3.0.0
---

# i18n reference

## Where the key list lives

**Call `get_i18n_schema`.** It returns every key the portal requires, in declaration order, each
with a line describing what it is for and which `{placeholders}` it must keep.

That list is derived from the `I18n` case class hierarchy in
`core/src/whitelabel/captal/core/i18n/I18n.scala`, flattened by `I18nSchema` in the same package —
the keys from the type via `Mirror`, the descriptions from an `I18n` instance the compiler requires
to be total. Neither can drift from what the portal actually reads. Adding a field to `I18n` adds
it to the required set, to the decoder, to the documented descriptions and to what
`get_i18n_status` reports as missing, all at once.

The same list is also published as the `i18n://required-keys` resource, which came first. Prefer
the tool: whether a model ever sees a resource is the host's decision, and in some clients
resources reach the operator's attachment menu and never the model at all.

This skill used to enumerate the keys itself. It went stale: it was missing
`welcome.authorized.title` and `welcome.authorized.remaining` (added with the access-expiry
countdown), stated a total of 36 when there were 37 listed and 39 real, and claimed a missing key
makes the i18n endpoint fail to decode. That is why the list is no longer written here, and why
nothing should re-enumerate it.

## What a missing key actually does

Nothing fails. The decoder substitutes the literal `[key]` — so `welcome.title` missing in Spanish
renders `[welcome.title]` on screen, in Spanish, to a real guest. There is no error, no log line,
and no failed deploy. **Auditing coverage is the only defence**, which is what `get_i18n_status`
is for: per locale it reports the required keys nothing has written, and the stored keys that are
no longer part of the schema.

## Key shape

- Keys are **flat** dotted strings. They look hierarchical because the `I18n` types nest, but what
  is stored and what `set_translations` takes is one flat key per row: `welcome.title`, not a
  nested object.
- The same set of keys has to exist in every locale a location serves. A locale with a smaller set
  is not a partial translation; it is a screen with `[key]` on it.
- Locale codes are ISO 639-1: `es`, `en`, `pt`.
- `set_translations` refuses the whole batch if any key is outside the schema, rather than applying
  it partly — a mistyped key would otherwise be stored and silently never rendered.

## Placeholders

Some keys interpolate runtime values — `{seconds}`, `{min}`, `{max}`. `get_i18n_schema` says which
ones, per key. A translation that drops the token renders it literally to the guest.

## Adding a locale

1. Pick the ISO 639-1 code.
2. `get_i18n_schema` for the keys, then `set_translations` for that locale carrying all of them.
3. `get_i18n_status` to confirm nothing is missing.

Nothing else is needed to make it selectable. The portal's locale picker is filled from
`GET /api/locales`, which returns the distinct locales of the stored translations — so a locale
appears there as soon as it has rows. The `List("es", "en")` in `WelcomeView` is only the value
shown before that request comes back.

## Source of truth

`core/src/whitelabel/captal/core/i18n/I18n.scala` for the keys, `I18nSchema.scala` for the
derivation. Adding a field to the former is a compile error until it is given a description, and
both the key list and the descriptions flow from it into `get_i18n_schema` automatically. **This
skill does not need updating when keys change.**

## Related

- `read_skill('edit-i18n')` — the procedure for writing and retiring translations
