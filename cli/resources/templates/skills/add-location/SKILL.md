---
name: add-location
description: Use this skill to onboard a new location end-to-end (initialize files, fill location.yaml, set i18n, add videos and promos, configure custom assets, deploy). Composes the smaller skills for a complete flow. Triggers on "agregar location", "add new location", "onboard location", "nuevo cliente", "new client", "create location".
version: 1.0.0
---

# Onboard a new location end-to-end

## Overview

A location is a single deployment of the captive portal (one ECS service, one ALB rule, one S3 prefix, one CloudFront invalidation scope). This skill walks through every artifact that needs to exist before `captal locations push <slug>`.

## Prerequisites

- `shared/captal.yaml` configured (use `configure-aws` if not)
- Shared surveys + advertisers deployed: `captal shared push` already ran for the cluster

## Steps

### 1. Initialize the location directory

```bash
captal locations add <slug>
```

This creates `locations/<slug>/` with template files:
```
locations/<slug>/
├── location.yaml           # name, ap_mac, optional desiredCount
├── i18n/
│   ├── es.yaml
│   └── en.yaml
└── assets/
    ├── styles.css          # custom CSS overrides (optional)
    └── brand-icon.svg      # custom logo (optional)
```

Pick a slug that's URL-safe (lowercase, alphanumeric + hyphens). It becomes the URL prefix: `https://<domain>/<slug>/`.

### 2. Fill `location.yaml`

```yaml
name: "Cafe Centro Plaza"      # human-readable name
ap_mac: "AA:BB:CC:DD:EE:01"    # MAC of the access point. Used to filter sessions.
desiredCount: 1                # optional; overrides ecs.desiredCount from captal.yaml
```

The `ap_mac` is critical: when a user device connects via a UniFi captive portal, the redirect URL includes `?ap=<mac>`. The API filters sessions by this MAC.

### 3. Edit i18n translations

Use the `edit-i18n` skill or open `locations/<slug>/i18n/{es,en}.yaml` directly. Translate the keys that should differ from defaults. Common keys:
- `welcome.title`, `welcome.subtitle`, `welcome.button.start`
- `survey.email.title`, `survey.profiling.*`
- `video.skip`, `video.next`
- `ready.title`

### 4. Add custom branding (optional)

Replace `locations/<slug>/assets/styles.css` with location-specific CSS overrides. Common targets: brand colors, button styles, logo size. The file is gzipped on upload and served as `./custom-styles.css.gz` from the SPA root, layered after the base styles.

Replace `locations/<slug>/assets/brand-icon.svg` with the location's logo.

If you don't customize, leave the templates as-is or remove the files entirely (the `<link onerror>` in `index.html` handles missing custom-styles gracefully).

### 5. Add advertiser videos

For each video the location should show, use `add-video`:
```bash
captal video add <slug> <advertiser-slug> path/to/video.mp4
```

Or, if the video is hosted externally and you only need the metadata, edit `locations/<slug>/videos/<advertiser-slug>-<video-name>/video.yaml` directly:

```yaml
advertiser: <advertiser-slug>     # must match a slug in shared/advertisers/
url: "https://..../video.mp4"
duration: 15
minWatch: 5
showCountdown: true
noRepeatSeconds: 3600
priority: 10
title:
  es: "Título es"
  en: "Title en"
description:
  es: "..."
  en: "..."
```

Each video can also have its own surveys under `videos/<...>/surveys/<question>.yaml` (use `add-video` skill for the full flow).

### 6. Add promo videos (optional)

Use `add-promo` for promotional content shown on the welcome screen. They live under `locations/<slug>/promo/`.

### 7. Deploy

```bash
captal locations push <slug>
```

This runs the full deploy flow (S3 copy of bundle, build derived ECR image, register task definition, create/update ECS service, ALB rule, CloudFront invalidation). See the `deploy-location` skill for what each step does.

### 8. Verify

Visit `https://<domain>/<slug>/` (production) and walk the flow. If errors appear, see `troubleshoot-deployment`.

## What lives where

| Artifact | Path | Purpose |
|---|---|---|
| `location.yaml` | `locations/<slug>/location.yaml` | Identity (name, AP MAC, desired count) |
| Translations | `locations/<slug>/i18n/{es,en}.yaml` | UI strings shown in the SPA |
| Custom styles | `locations/<slug>/assets/styles.css` | CSS overrides for brand customization |
| Brand logo | `locations/<slug>/assets/brand-icon.svg` | Custom logo for the loading splash |
| Advertiser videos | `locations/<slug>/videos/<adv>-<video>/` | Per-location video content + per-video surveys |
| Promo videos | `locations/<slug>/promo/<name>.yaml` | Welcome-screen promotional videos |

## Idempotency

`locations push <slug>` is fully idempotent — running it twice in a row produces the same end state. The provisioning system uses content hashing to detect changes; only modified entities are applied.
