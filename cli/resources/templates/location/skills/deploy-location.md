# Deploy a location

## Overview
Deploying a location uploads assets to S3, creates/updates an ECS service, and configures ALB routing.

## Prerequisites
- `/etc/captal/captal.yaml` configured
- Location initialized: `/etc/captal/locations/<slug>/`
- Shared resources deployed: `captal shared push`

## Steps

1. Review your location files:
   - `location.yaml` — name, ap_mac, optional desiredCount
   - `i18n/` — translations for each locale
   - `videos/` — advertiser videos with optional surveys
   - `promo/` — promotional videos
   - `assets/` — custom CSS and branding

2. Deploy: `captal push <slug>`

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
