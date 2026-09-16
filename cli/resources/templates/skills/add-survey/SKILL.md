---
name: add-survey
description: Use this skill when adding or changing identification surveys (email, profiling, location) or advertiser surveys through the backoffice MCP server. Triggers on "add survey", "create survey", "new survey", "add question", "survey question".
version: 2.0.0
---

# Add a survey

Surveys are created with tools, not files. Read `get_survey` output before changing anything that
already exists.

## Order of operations

1. `list_surveys` — see what is already there. Identification surveys are picked by category alone,
   so a second active survey of the same category is refused.
2. `create_survey` — category is `email`, `profiling`, `location` or `advertiser`. An advertiser
   survey must name the `videoId` of the video it follows; without it the survey exists but is
   never reachable.
3. `create_question` once per question, in the order you want them asked. Each is appended last.
4. `reorder_questions` if the order needs changing later. It takes every active question of the
   survey exactly once.

## Question types

| Type | Options | Rules |
|---|---|---|
| `radio`, `checkbox`, `dropdown` | required | `checkbox` takes selection rules; `radio` and `dropdown` take none |
| `input` | none | text rules: `min_length`, `max_length`, `pattern`, `email`, `url` |
| `rating` | none | integer range: `min`, `max` |
| `numeric` | none | numeric range |
| `date` | none | date range |

A rule that does not match its question type is refused at the write. That check exists because the
read path drops such a rule silently: it would sit in the table and never apply.

## Texts are per locale, and nothing audits them

Question and option texts are stored per locale. Give every locale the location serves, in one call:

```json
"text": [{"locale": "es", "value": "¿Cuántos años tenés?"},
         {"locale": "en", "value": "How old are you?"}]
```

**`i18n_status` will not tell you if you forget one.** It reports the portal's interface keys, which
are a different category of text. A question with only English text does not render a placeholder —
a guest browsing in Spanish is simply shown the English wording, with nothing logged anywhere. The
only way to catch it is `get_survey`, which lists every locale each text has.

## Retiring a question

`set_question_active` with `active: false`. Questions are never deleted: `answers.question_id`
references them, and a retired question has to keep resolving for every answer already recorded.
The tool reports how many that is.

Retiring the last active question leaves a survey that can never be served. The response says how
many remain so that is visible rather than discovered through an empty flow.

## Related

- `skill://add-advertiser/SKILL.md` — advertiser surveys attach to a video, which belongs to an
  advertiser
- `skill://edit-i18n/SKILL.md` — the portal's own interface text, a separate category
