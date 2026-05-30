---
name: deploy-location
description: Use this skill when deploying a location to AWS. Triggers on "deploy location", "push location", "captal locations push", "provision location".
version: 1.0.0
---

# Deploy a location

## Overview
Deploying a location uploads assets to S3, creates/updates an ECS service, and configures ALB routing.

## Prerequisites
- `shared/captal.yaml` configured
- Location initialized: `locations/<slug>/`
- Shared resources deployed: `captal shared push`

## Steps

1. Review your location files:
   - `locations/<slug>/location.yaml` — name, ap_mac, optional desiredCount
   - `locations/<slug>/i18n/` — translations for each locale
   - `locations/<slug>/videos/` — advertiser videos with optional surveys
   - `locations/<slug>/promo/` — promotional videos
   - `locations/<slug>/assets/` — custom CSS and branding

2. Deploy: `captal locations push <slug>`

3. The CLI will:
   - Build and upload client assets to S3
   - Register a new ECS task definition with the location config
   - Create or update the ECS service (with configured replicas)
   - Configure ALB routing rule for `<slug>.<domain>`

## Configuration in location.yaml
```yaml
name: "Location Name"
desiredCount: 2                   # optional, overrides ecs.desiredCount from captal.yaml
unifi:                            # required for UniFi-managed locations (Integration v1 API)
  host: "192.168.1.1"
  apiToken: "<API_KEY>"
  apMac: "AA:BB:CC:DD:EE:FF"      # AP MAC — used by the dispatcher Lambda for slug lookup
  siteId: "<UUID>"                # discover via GET /proxy/network/integration/v1/sites
  port: 443                       # optional (default 443)
  defaultDurationMinutes: 1440    # optional (default 24h)
  redirectUrl: ""                 # optional override of the dispatcher's redirect target
```

See `yaml-reference` and `add-location` skills for the full schema, including how to obtain the `siteId` and `apMac`.

## What happens on re-deploy
- Assets are overwritten in S3
- A new ECS task definition is registered
- The service is updated with force new deployment
- ALB rule is updated if needed
- Provisioning runs on container startup (idempotent — only changes are applied)
