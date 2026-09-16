---
name: add-survey
description: Use this skill when adding or changing identification surveys (email, profiling, location) or advertiser surveys through the backoffice MCP server. Triggers on "add survey", "create survey", "new survey", "add question", "survey question".
version: 2.1.0
---

# Add a survey

Surveys are created with tools, not files. Read `get_survey` output before changing anything that
already exists.

## Order of operations

1. `list_surveys` — see what is already there. Identification surveys are picked by category alone,
   so a second active survey of the same category is refused.
2. `create_survey` — category is `email`, `profiling`, `location` or `advertiser`. An advertiser
   survey names the `videoId` of the video it follows and inherits that video's location; there is
   no location to pass. The video is verified: naming one that does not exist is refused, since no
   foreign key would catch it and the survey would simply be unreachable.
3. `create_question` once per question, in the order you want them asked. Each is appended last.
4. `reorder_questions` if the order needs changing later. It takes every active question of the
   survey exactly once.

## What to ask the operator, and what not to

**Only the wording has to come from them.** The schema marks three fields required —
`surveyId`, `questionType` and `text` — and everything else has a default or follows from the
type. Do not ask about an optional field unless the operator raises it.

| Field | Where it comes from |
|---|---|
| `text` | the operator; nothing else can supply it |
| `questionType` | the shape of the answer being collected — infer it |
| `options` | the operator, and only for `radio`, `checkbox`, `dropdown` |
| `required` | defaults to true |
| `points` | defaults to 10, which is what every existing question uses |
| `displayOrder` | automatic; the question is appended last |
| `description`, `placeholder` | omit unless asked for |
| `hierarchyLevel` | only in a location survey, and only if the operator is building a hierarchy |

A plain text question is one call with nothing but the wording:

```json
{"surveyId": "…", "questionType": "input",
 "text": [{"locale": "es", "value": "¿En qué barrio vivís?"}]}
```

## Question types

| Type | Options | Rules it accepts |
|---|---|---|
| `radio`, `dropdown` | required | none |
| `checkbox` | required | `min_selections`, `max_selections` |
| `input` | none | `min_length`, `max_length`, `pattern`, `email`, `url` |
| `rating` | none | `min`, `max` — whole numbers |
| `numeric` | none | `min`, `max` — numbers |
| `date` | none | `min`, `max` — ISO dates, `yyyy-mm-dd` |

## Validation rules

Rules are named, not written as JSON. Give a `kind` and a `value`; the server builds the stored
config, so its shape is always the one the portal can read.

```json
"rules": [{"kind": "min_length", "value": "3"},
          {"kind": "pattern", "value": "^[A-Z].*"}]
```

`email` and `url` take no value. Everything else does, as text — the server parses it and rejects a
`min_length` of `"abc"` or a date `min` that is not `yyyy-mm-dd`.

Pairing a rule with a type that does not accept it is refused at the write, naming both. That check
is the only thing standing between a wrong rule and silence: the read path decodes rules with
`decode(config).toOption` and flat-maps, so one it cannot read is dropped at runtime with no error
anywhere.

## Texts are per locale, and nothing audits them

Every question and every option needs text in each locale that will see it. Give them all in one
call:

```json
"text": [{"locale": "es", "value": "¿Cuántos años tenés?"},
         {"locale": "en", "value": "How old are you?"}]
```

**Which locales "each" means depends on the category:**

- **Identification surveys are global.** `SurveyRepositoryQuill` picks them by category with no
  location filter anywhere, so one profiling question is asked at every location. It needs every
  locale any location serves, not just the one you had in mind.
- **Advertiser surveys follow their video**, and videos are filtered by location, so those questions
  only ever reach the locations running that video.

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
