---
name: yaml-reference
description: Reference for every YAML file in a captal CLI project — directory layout, schema of each file type, required vs optional fields, and validation rules. Use when editing or reviewing YAML by hand. Triggers on "edit yaml", "yaml schema", "yaml structure", "what fields", "estructura yaml", "campos yaml".
version: 1.0.0
---

# YAML reference for a captal CLI project

This is the orientation/schema doc. For task-specific flows use the dedicated skills (`add-survey`, `add-advertiser`, `add-video`, `add-promo`, `add-location`, `edit-i18n`, `configure-aws`).

## Directory layout

```
<project root>/
├── shared/
│   ├── captal.yaml             # AWS + cluster config (one per cluster)
│   ├── surveys/
│   │   ├── email.yaml          # category: email
│   │   ├── profiling.yaml      # category: profiling
│   │   └── location.yaml       # category: location
│   └── advertisers/
│       └── <slug>.yaml         # one file per advertiser, filename = slug
└── locations/
    └── <location-slug>/
        ├── location.yaml       # name, ap_mac, optional desiredCount
        ├── i18n/
        │   ├── es.yaml         # flat key → string map (Spanish)
        │   └── en.yaml         # flat key → string map (English)
        ├── assets/
        │   ├── styles.css      # custom CSS overrides (optional)
        │   └── brand-icon.svg  # custom logo (optional)
        ├── promo/
        │   └── <name>.yaml     # promo video, filename = promo slug
        └── videos/
            └── <advertiser>-<video>/
                ├── video.yaml          # advertiser video
                └── surveys/
                    └── <name>.yaml     # per-video survey, filename = survey slug
```

Filenames matter — slugs are derived from filenames (without `.yaml`). Keep them URL-safe: lowercase, alphanumeric, hyphens. No spaces, no underscores in slugs (underscores are accepted in YAML *fields*, not slugs).

## `shared/captal.yaml` — cluster + AWS config

Read by the CLI to know where to push. Not consumed by the SPA or API.

```yaml
aws:
  region: us-east-1
  profile: default              # optional; uses env credentials if omitted

images:
  api: "<account>.dkr.ecr.<region>.amazonaws.com/captal-api-dev:v1.0.0"
  provision: "<account>.dkr.ecr.<region>.amazonaws.com/captal-provision-dev:v1.0.0"
  shared: "<account>.dkr.ecr.<region>.amazonaws.com/captal-shared-dev"
  locations: "<account>.dkr.ecr.<region>.amazonaws.com/captal-locations-dev"

s3:
  bucket: captal-dev-assets
  bundlePrefix: bundle/

ecs:
  cluster: captal-dev
  subnets: [subnet-xxx, subnet-yyy]
  securityGroups: [sg-xxx]
  executionRoleArn: "arn:aws:iam::<account>:role/captal-dev-ecs-execution"
  taskRoleArn: "arn:aws:iam::<account>:role/captal-dev-ecs-task"
  desiredCount: 1               # default for locations; can be overridden per-location
  cpu: 256                      # task-level CPU (Fargate)
  memory: 512                   # task-level memory MiB

alb:
  listenerArn: "arn:aws:elasticloadbalancing:..."
  vpcId: vpc-xxx
  domain: production.captal.example.com
  healthCheckPath: /health      # without slug prefix — task uses /<slug>/api/health internally

cloudfront:
  distributionId: EXXXXXXXXX

database:
  url: "jdbc:rqlite:http://rqlite.captal.local:4001"

server:
  devEndpoints: false           # true exposes /api/admin/reset endpoint for dev
```

Use `configure-aws` skill to populate from terraform outputs.

## `shared/surveys/<category>.yaml` — identification surveys

Three reserved categories: `email`, `profiling`, `location`. Filename should match the category.

```yaml
category: profiling             # MUST be one of: email | profiling | location
questions:
  - type: input
    points: 10                  # default 10
    required: true              # default true
    text:
      es: "Pregunta en español"
      en: "Question in English"
    description:                # optional helper text
      es: "..."
      en: "..."
    placeholder:                # optional, for input type
      es: "..."
      en: "..."
    rules:                      # optional validation
      - type: email
      - type: max_length
        value: 100
      - type: pattern
        value: "^[A-Z]{2}$"

  - type: dropdown
    points: 5
    text: { es: "Estado", en: "State" }
    hierarchyLevel: state       # for cascading dropdowns: country | state | city
    options:
      - text: { es: "Ciudad de México", en: "Mexico City" }
      - text: { es: "Jalisco", en: "Jalisco" }
```

### Question types
| `type`     | Notes                                                |
|------------|------------------------------------------------------|
| `input`    | Free text. Supports `rules`, `placeholder`.          |
| `radio`    | Single choice. Requires `options` (≥2).              |
| `checkbox` | Multi choice. Requires `options` (≥2).               |
| `dropdown` | Single dropdown. Requires `options`. Supports `hierarchyLevel`. |
| `rating`   | Numeric rating (e.g. 1–5 stars).                     |
| `numeric`  | Numeric input.                                       |
| `date`     | Date picker.                                         |

### Rule types
- `email` — value-less, validates email format
- `max_length` — value: integer
- `pattern` — value: regex string

## `shared/advertisers/<slug>.yaml`

```yaml
name: "Google Play"             # display name
priority: 10                    # higher = more frequently shown (default 10)
```

Filename = advertiser slug. Referenced by videos via the `advertiser:` field — it must match an existing advertiser slug.

## `locations/<slug>/location.yaml`

```yaml
name: "Cafe Centro Plaza"       # human-readable name
ap_mac: "AA:BB:CC:DD:EE:01"     # AP MAC; optional but used to filter sessions by AP
desiredCount: 1                 # optional; overrides ecs.desiredCount from captal.yaml
```

Slug comes from the directory name, not from a YAML field.

## `locations/<slug>/i18n/{es,en}.yaml`

Flat key → string map. Dots are part of the key, not nested objects.

```yaml
"welcome.title": "Bienvenido a Cafe Centro"
"welcome.subtitle": "Conéctate gratis"
"welcome.button.start": "Comenzar"
"survey.email.title": "Tu correo"
"video.skip": "Saltar"
"video.next": "Siguiente"
"ready.title": "¡Listo!"
```

Both files should have the same keys. Missing keys fall back to the default bundle.

## `locations/<slug>/videos/<advertiser>-<video>/video.yaml`

Directory name convention: `<advertiser-slug>-<video-name>`.

```yaml
advertiser: chromecast          # MUST match a slug in shared/advertisers/
url: "https://cdn.example.com/blazes.mp4"
duration: 30                    # seconds
minWatch: 5                     # seconds before "next" enables (default 5)
showCountdown: true             # default true
noRepeatSeconds: 3600           # optional; throttle re-show per session
priority: 10                    # default 10
title:
  es: "Blazes"
  en: "Blazes"
description:                    # optional
  es: "..."
  en: "..."
```

## `locations/<slug>/videos/<...>/surveys/<name>.yaml` — per-video surveys

Same `QuestionYaml` schema as identification surveys, wrapped in `VideoSurveyYaml`:

```yaml
name: "Interés en el producto"  # optional; defaults to filename
questions:
  - type: radio
    text: { es: "¿Te interesa?", en: "Are you interested?" }
    options:
      - text: { es: "Sí", en: "Yes" }
      - text: { es: "No", en: "No" }
```

## `locations/<slug>/promo/<name>.yaml` — promotional videos

No advertiser. Shown on the welcome screen.

```yaml
url: "https://cdn.example.com/promo.mp4"
duration: 10
minWatch: 3                     # default 3
showCountdown: false            # default false
priority: 1                     # default 1
title:
  es: "Promoción"
  en: "Promo"
description:                    # optional
  es: "..."
  en: "..."
```

## Cross-cutting rules

- **All `Map[String, String]` fields** (`text`, `description`, `placeholder`, `title`) require the same locales as `i18n/`. Today: `es` and `en`.
- **Empty optional fields** should be omitted entirely — don't set them to `null` or `""`.
- **YAML quoting**: keys with dots (`"welcome.title"`) MUST be quoted. ARNs and URLs SHOULD be quoted to avoid `:` ambiguity.
- **No tabs** — YAML requires spaces. Indent with 2 spaces.
- **Slug rule**: filenames and directory names that act as slugs (`<advertiser>.yaml`, `<location>/`, `<advertiser>-<video>/`, `<promo>.yaml`, `<survey>.yaml`) must be lowercase alphanumeric with hyphens. The system uses these names as identifiers — renaming a file is a destructive change that creates a new entity rather than renaming.

## Content hashing & idempotency

`captal shared push` and `captal locations push <slug>` are idempotent. The provision system content-hashes each YAML file and only applies changes for entities whose hash changed. Reformatting a YAML (whitespace, comment changes) without changing semantic content does NOT trigger an update.

## Where the schemas come from

The authoritative schemas are Scala case classes in `provision/src/whitelabel/captal/infra/provision/models.scala` (LocationYaml, SurveyYaml, QuestionYaml, AdvertiserYaml, VideoYaml, PromoVideoYaml, VideoSurveyYaml). If a YAML editor is unsure about a field, that file is the source of truth.
