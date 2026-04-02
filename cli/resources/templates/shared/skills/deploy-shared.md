# Deploy shared resources

## Overview
Shared resources (surveys and advertisers) are deployed via an ephemeral ECS task that provisions data to the database.

## Prerequisites
- `/etc/captal/captal.yaml` configured with AWS credentials and infrastructure details
- Shared resources defined in `/etc/captal/shared/`

## Steps

1. Review changes in `surveys/` and `advertisers/`
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
- Verify `captal.yaml` has correct `database.url` and `ecs.*` settings
