---
name: recover-data
description: Use this skill when the SPA is missing surveys, videos, or i18n after they were previously provisioned, or when the API returns 500 with "No result set returned" / "Internal server error". Triggers on "no veo encuestas", "faltan videos", "no result set", "rqlite perdió datos", "recover data", "data missing".
version: 1.0.0
---

# Recover data after rqlite loss

## Overview

The rqlite cluster runs on Fargate ephemeral storage. When nodes are redeployed (rolling restarts, scaling, image upgrades) data on individual nodes is lost; the cluster recovers via Raft replication. If the cluster temporarily loses quorum (2/3 nodes down at the same time), recently-written data can be lost permanently. Separately, the API task's JDBC connection becomes stale when its rqlite peer disappears, causing query failures even when data is present.

This skill walks through detection and recovery.

## Symptoms

| Symptom | Likely cause |
|---|---|
| `/api/locales`, `/api/status`, `/api/i18n/*` return 500 with `SQLException: No result set returned` | API task's JDBC connection is stale (rqlite node it was talking to disappeared) |
| Welcome shows but identification questions don't render (jumps to advertiser video) | Shared surveys missing from rqlite (data lost) |
| "No videos available" on advertiser-video phase | Location videos missing from rqlite (data lost) |
| `/api/locales` returns 500 even after CDN refresh | i18n data missing from rqlite |

## Detection

Check service health and recent rqlite logs:
```bash
# 1. ECS service still running?
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].{Running:runningCount,Desired:desiredCount}'

# 2. Recent API errors?
aws logs tail /ecs/captal-<slug> --since 5m | grep -i 'no result set\|sqlexception'

# 3. rqlite cluster recently churned?
aws logs tail /ecs/<project>-rqlite --since 30m | grep -iE 'leader|elected'
```

## Recovery

### Step 1 — Force-deploy API service for fresh JDBC connections

```bash
aws ecs update-service \
  --cluster <cluster> --service captal-<slug> \
  --force-new-deployment
```

Wait ~1-2 min for the new task to start and stabilize. Verify:
```bash
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].{Running:runningCount,Desired:desiredCount}'
```

### Step 2 — If still missing data, re-run shared push

```bash
captal shared push
```

This launches an ephemeral ECS task that re-applies `shared/surveys/*` and `shared/advertisers/*` to rqlite. The provisioning is idempotent.

### Step 3 — Re-run locations push for the affected slug

```bash
captal locations push <slug>
```

This rebuilds/redeploys the location image (which re-runs ProvisionService at startup against the location's YAMLs) and reinserts location, i18n, videos, promos.

### Step 4 — Verify

Open the SPA at `https://<domain>/<slug>/` (with cache cleared) and walk the flow:
- Welcome → continue → identification questions appear → fill them → advertiser video plays → survey → ready

If still failing, escalate to `troubleshoot-deployment` skill.

## Prevention

This is a known weakness of the current setup; permanent fixes are tracked as TODOs in the project AGENTS.md (EFS migration for rqlite, JDBC connection pool resilience). For now, prefer to:
- Avoid bouncing more than 1 rqlite node at a time
- Keep `shared/` and `locations/<slug>/` YAMLs in version control as the source of truth (the DB is reconstructible from them via `shared push` + `locations push`)
