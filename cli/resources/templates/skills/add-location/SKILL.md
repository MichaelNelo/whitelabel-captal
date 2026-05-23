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

# Optional — UniFi Controller access for guest authorization
unifi:
  host: "192.168.1.1"               # IP or hostname of the Controller on the LAN
  apiToken: "<API_KEY>"             # generated in the Controller UI (see below)
  port: 8443                        # default 8443; UnifiOS uses 443
  site: "default"                   # site name in the Controller (typically "default")
  unifiOs: true                     # true for UDM / Dream Machine; false for standalone Controllers
  defaultDurationMinutes: 1440      # how long the authorize-guest grant lasts (24h default)
```

The `ap_mac` is critical: when a user device connects via a UniFi captive portal, the redirect URL includes `?ap=<mac>`. The API filters sessions by this MAC.

The `unifi` block is optional. Locations without it are still provisioned but cannot grant internet access through the Controller: the `UnifiAuthorizationHandler` (post-commit) detects no config, logs "skipping authorization", and the user's session stays in `Phase.Ready` (the SPA's thank-you page) instead of progressing to `Authorized`. Provide it when the location has a UniFi Controller reachable from wherever the captal API runs — directly (same LAN) or via the `unifi.proxyUrl` configured in `shared/captal.yaml` (tinyproxy + Tailscale on cloud deploys).

#### How to get the `apiToken`

On the Controller UI:

1. Open **UniFi OS** (UDM / Dream Machine) → **Settings** → **Control Plane** → **Integrations** → **Create API Key**. Give it a descriptive name (e.g. `captal-portal-cafe-centro`) and copy the token immediately (it is shown only once).
2. On older standalone Controllers without UnifiOS, create a dedicated local admin user with limited permissions and use its credentials instead — that path is not covered by the `apiToken` field yet; coordinate before deploying.

#### Typical values per hardware

| Hardware                                | `host`                     | `port` | `unifiOs` |
|-----------------------------------------|----------------------------|--------|-----------|
| Dream Machine / DM Pro / DM SE          | LAN IP (often the gateway) | 443    | true      |
| Cloud Key Gen2+ (UnifiOS)               | LAN IP                     | 443    | true      |
| Network Application standalone (Linux / Docker) | LAN IP             | 8443   | false     |

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

### 8. Verify with a test URL

The captive portal expects to be reached via a UniFi redirect that carries identifying params in the query string. Construct a test URL using the location's `ap_mac` from `location.yaml`:

```
https://<domain>/<slug>/?id=<CLIENT_MAC>&ap=<AP_MAC>&ssid=<SSID>&url=<ORIGINAL_URL>&click_id=<CLICK_ID>
```

> **Agent instruction**: every time the user asks for a test URL, generate a fresh randomized
> `click_id` — never reuse the previous one or a hard-coded literal. Each UniFi redirect in
> production carries a unique click_id and the API persists it on the session row for
> attribution; reusing the same token defeats the purpose. Suggested format: a short hex/UUID
> like `test-$(uuidgen)` or `test-<8-hex-chars>` (e.g. `test-a3f9b21c`). If you have shell
> access: `printf 'test-%s\n' "$(openssl rand -hex 4)"`.

Concrete example for a location with `ap_mac: "AA:BB:CC:DD:EE:01"` (the `click_id` shown below is illustrative — generate a new one each time):
```
https://production.captal.centauroads.com/cafe-centro/?id=11:22:33:44:55:66&ap=AA:BB:CC:DD:EE:01&ssid=CafeCentroGuest&url=http%3A%2F%2Fexample.com&click_id=test-a3f9b21c
```

#### Query param reference

These are the params UniFi appends when it redirects an unauthenticated client to the captive portal. The SPA reads them on load (`parseCaptivePortalHeaders` in `client/Main.scala`) and forwards them to the API as `X-*` headers on the first `/api/status` call.

| URL param  | Forwarded as header  | Required | Source         | Purpose                                                                                  |
|------------|----------------------|----------|----------------|------------------------------------------------------------------------------------------|
| `id`       | `X-Client-Mac`       | yes      | client device  | MAC address of the user's device. Identifies the user across visits.                      |
| `click_id` | `X-Click-Id`         | yes      | UniFi          | Per-redirect identifier (typically a unique token UniFi generates per click). Stored on the session for attribution. |
| `ap`       | `X-Ap-Mac`           | no       | UniFi AP       | MAC of the access point the client connected to. **Should match `ap_mac` in `location.yaml`** — soft-validated, mismatches get a warning log. |
| `ssid`     | `X-Ssid`             | no       | UniFi AP       | SSID (network name) the client connected to. Logged for analytics; not validated.         |
| `url`      | `X-Redirect-Url`     | no       | UniFi AP       | Original URL the user tried to visit before redirect. Logged; the SPA does not redirect to it (the user finishes the flow on the portal). |

**Required params** (`id` and `click_id`) MUST be present when a session is being created. Missing either causes `ApiError.SessionMissing` and the SPA falls back to the error page. On subsequent requests where a session cookie is already set, these params are ignored.

Real captive-portal traffic from UniFi looks like `?id=<mac>&ap=<mac>&t=<timestamp>&url=<original>&ssid=<ssid>&click_id=<token>`; the `t` (timestamp) param is ignored by the SPA.

#### What to test

1. Open the test URL in a fresh incognito window (so no existing session cookie is in play).
2. Walk the flow: Welcome → identification surveys → advertiser video → video survey → Ready.
3. In DevTools → Application → Cookies, confirm a cookie named `captal_session_<slug>` exists with `Path=/<slug>`.
4. (Optional) Open a second incognito window with a *different* `id=` MAC and confirm a separate session is created (the API differentiates by client MAC + AP MAC pair).

If errors appear, see `troubleshoot-deployment`.

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
