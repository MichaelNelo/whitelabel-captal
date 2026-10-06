---
name: add-survey
description: Use this skill when adding or changing identification surveys (email, profiling, location) or advertiser surveys through the backoffice MCP server. Triggers on "add survey", "create survey", "new survey", "add question", "survey question".
version: 3.0.0
---

# Add a survey

Surveys are created with tools, not files. Read `get_survey` output before changing anything that
already exists.

## Order of operations

1. `list_surveys` — see what is already there. Identification surveys are picked by category alone,
   so a second active survey of the same category is refused.
2. Creating it depends on which kind it is, and they are different tools on purpose:
   - `create_survey` for the survey that follows a video — an advertiser's or one of the venue's
     own promos; both are stored under the `advertiser` category. It names the `videoId` of the
     video it follows and inherits that video's location and advertiser; there is nothing to pass
     about either. The video is verified: naming one that does not exist is refused, since no
     foreign key would catch it and the survey would simply be unreachable.
     `create_advertiser_video` and `create_promo_video` already create it, so reach for this only
     to give an existing promo its first survey.
   - `create_identification_survey` for `email`, `profiling` or `location`. These are
     deployment-wide — every guest of every location answers them — so it sits behind the
     `identity:write` scope rather than `content:write`. A token that edits one location's content
     cannot reach it, and that is deliberate: replacing one of these changes the identification
     flow for every portal at once.
3. `create_question` once per question, in the order you want them asked. Each is appended last.
4. `reorder_questions` if the order needs changing later. It takes every active question of the
   survey exactly once.

**Steps 3 and 4 come in pairs, and the identification half has its own.** Use
`create_identification_question`, `reorder_identification_questions` and
`set_identification_question_active` for an `email`, `profiling` or `location` survey; the
unsuffixed names only accept a video's survey, and each refuses the other half by naming the tool
that does take it. The split is the scope: the identification tools require `identity:write`, so a
tool cannot publish one requirement in `tools/list` and enforce another.

## What to ask the operator, and what not to

**Only the wording has to come from them.** The schema marks three fields required —
`surveyId`, `text` and `answer` — and everything else has a default or follows from the type. Do not ask about an optional field unless the operator raises it.

| Field | Where it comes from |
|---|---|
| `text` | the operator; nothing else can supply it |
| `answer.kind` | how the answer is collected — infer it |
| `answer.options` | the operator, and only where the variant has the field |
| `required` | defaults to true |
| `points` | defaults to 10, which is what every existing question uses |
| `displayOrder` | automatic; the question is appended last |
| `description`, `placeholder` | omit unless asked for |
| `hierarchyLevel` | only in a location survey, and only if the operator is building a hierarchy |

A plain text question is one call with nothing but the wording:

```json
{"surveyId": "…",
 "text": [{"locale": "es", "value": "¿En qué barrio vivís?"}],
 "answer": {"kind": "Input"}}
```

## The question shape

What varies by type lives in `answer`, which is discriminated. The schema carries the constraint,
so an `Input` variant has no `options` field to pass and a `Radio` has no `rules`:

```json
{"surveyId": "…",
 "text": [{"locale": "es", "value": "¿En qué barrio vivís?"}],
 "answer": {"kind": "Input"}}
```

```json
{"answer": {"kind": "Checkbox",
            "options": [{"text": [{"locale": "es", "value": "Mañana"}]}],
            "rules": [{"kind": "min_selections", "value": "1"}]}}
```

| `answer.kind` | Carries |
|---|---|
| `Radio`, `Dropdown` | `options` |
| `Checkbox` | `options`, and selection rules: `min_selections`, `max_selections` |
| `Input` | text rules: `min_length`, `max_length`, `pattern`, `email`, `url` |
| `Numeric` | range rules `min`, `max` — any number |
| `Date` | range rules `min`, `max` — ISO dates, `yyyy-mm-dd` |

There is no `Rating`, and asking for one is refused. The portal has no input for it — the view
matches the case and renders the literal text `Rating input - TODO`, which is what a guest would
read. Ask for a rating as a `Radio` with the points as options: a one-to-five scale is five
options, and that is something the portal can display today.

Passing a field the variant does not have is refused, as is a rule kind that belongs to another
type. Neither reaches validation — the shape makes them undecodable — which is why the pairing no
longer has to be remembered. `email` and `url` take no value; everything else does, as text.

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

**`get_i18n_status` will not tell you if you forget one.** It reports the portal's interface keys, which
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

- `skill://add-video/SKILL.md` — `create_advertiser_video` writes the survey and its questions in
  the same call, and requires them
- `skill://add-promo/SKILL.md` — a promo's survey is optional, and why
- `skill://add-advertiser/SKILL.md` — advertiser surveys attach to a video, which belongs to an
  advertiser
- `skill://edit-i18n/SKILL.md` — the portal's own interface text, a separate category
