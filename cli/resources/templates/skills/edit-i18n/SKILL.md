---
name: edit-i18n
description: Use this skill when editing translations or adding locales. Triggers on "edit translations", "i18n", "add locale", "translation yaml", "internationalization".
version: 1.0.0
---

# Edit translations (i18n)

## Overview
Each location has its own translations in `locations/<slug>/i18n/`. These are frontend UI strings shown to users.

## Structure
```
locations/<slug>/i18n/
├── es.yaml    # Spanish
└── en.yaml    # English
```

## Format
Flat key-value pairs:
```yaml
welcome_title: "Bienvenido"
welcome_subtitle: "Responde encuestas y gana recompensas"
btn_continue: "Continuar"
btn_submit: "Enviar"
btn_skip: "Omitir"
```

## Adding a new locale
1. Create a new file `locations/<slug>/i18n/<locale>.yaml` (e.g., `pt.yaml` for Portuguese)
2. Include all the same keys as the other locale files
3. Deploy: `captal locations push <location-slug>`

## Notes
- Each location can have different translations (e.g., different branding per location)
- The locale code must be a valid ISO 639-1 code
- All keys should be present in all locale files to avoid fallback to placeholders
