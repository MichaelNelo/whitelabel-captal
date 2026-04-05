---
name: deploy-shared
description: Use this skill when deploying shared resources (surveys, advertisers). Triggers on "deploy shared", "push shared", "captal shared push", "provision shared".
version: 1.0.0
---

# Deploy shared resources

## Overview
Shared resources (surveys and advertisers) are deployed via an ephemeral ECS task that provisions data to the database.

## Prerequisites
- `shared/captal.yaml` configured with AWS credentials and infrastructure details
- Shared resources defined in `shared/`

## Steps

1. Review changes in `shared/surveys/` and `shared/advertisers/`
2. Run: `captal shared push`
3. The CLI will:
   - Register an ephemeral ECS task definition
   - Run the task (provisions surveys + advertisers to DB)
   - Wait for completion and report status

## What gets provisioned
- **Surveys**: upserted by category (email, profiling, location)
- **Advertisers**: upserted by slug (filename)
- Shared provisioning is additive — it never deletes existing data

## Troubleshooting
- Check CloudWatch logs at `/ecs/captal-shared` for provisioning errors
- Verify `shared/captal.yaml` has correct `database.url` and `ecs.*` settings
