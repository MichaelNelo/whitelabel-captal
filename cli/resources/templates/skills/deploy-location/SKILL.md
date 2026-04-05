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
ap_mac: "AA:BB:CC:DD:EE:FF"     # MAC of the access point
desiredCount: 2                   # optional, overrides ecs.desiredCount from captal.yaml
```

## What happens on re-deploy
- Assets are overwritten in S3
- A new ECS task definition is registered
- The service is updated with force new deployment
- ALB rule is updated if needed
- Provisioning runs on container startup (idempotent — only changes are applied)
