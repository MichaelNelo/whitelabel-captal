---
name: troubleshoot-deployment
description: Use this skill when something is broken after deploy — health checks failing, 500 errors, CloudFront 403s, ECS service stuck, browser shows wrong content. Maps common symptoms to diagnostic commands and resolutions. Triggers on "no funciona", "broken", "error", "503", "502", "500", "404", "403", "unhealthy", "stuck", "drain", "no carga", "troubleshoot", "diagnose", "debug".
version: 1.0.0
---

# Troubleshoot deployment issues

## Overview

This skill is a symptom → cause → fix cheat sheet for the most common deployment problems. For each symptom, there's a quick check command and the resolution path.

## A. SPA loads HTML but assets 404 or 403

### Symptom
- Browser console shows `main.js.gz` or `styles.css.gz` failing to load
- Page is blank or unstyled

### Diagnose
```bash
curl -sI https://<domain>/<slug>/main.js.gz
# Expected: HTTP/2 200, content-type: application/javascript, content-encoding: gzip
```

If 403: bucket policy missing `s3:ListBucket` (returns 403 on missing keys instead of 404). See `troubleshoot-deployment` section "B" below.

If 404 of files that should exist: bundle wasn't copied to `<slug>/` prefix. Re-run `captal locations push <slug>`.

If files load but content-encoding is missing or wrong: the bundle was uploaded with `aws s3 sync` instead of `aws s3 cp ... --content-encoding gzip`. The release flow needs the metadata explicitly. See project AGENTS.md → "Bundle release flow".

## B. SPA gets 403 on `custom-styles.css.gz` (the optional custom CSS)

### Symptom
- `<link rel="stylesheet" href="./custom-styles.css.gz">` fails with 403
- `onerror="this.remove()"` doesn't fire (browsers only fire onerror reliably on 404)

### Cause
S3 with private bucket + OAC returns 403 for missing keys when the principal lacks `s3:ListBucket`. CloudFront forwards the 403.

### Fix
The bucket policy needs both `s3:GetObject` on `/*` AND `s3:ListBucket` on the bucket itself. This is the project's responsibility to provide via Terraform — escalate to project ops if it's missing.

## C. Browser at `/<slug>/<spa-route>` requests assets from `/<slug>/<spa-route>/`

### Symptom
- Reload at e.g. `/cafe-centro/video/` makes browser request `/cafe-centro/video/main.js.gz` (404)
- Reload at e.g. `/cafe-centro/video` (no trailing slash) makes browser request `/main.js.gz` (404)

### Cause
Relative URLs in `index.html` resolve against `window.location.pathname`, not the SPA root.

### Fix
The bundle must include an inline `<script>` at the top of `<head>` that injects `<base href="/<slug>/">` based on the URL. If you're seeing this, the bundle is stale or out of date — re-bundle and re-push (see project AGENTS.md → "Bundle release flow"). The CloudFront SPA-fallback function should also handle the slug-root redirect.

## D. ECS service stays in DRAINING / can't UpdateService

### Symptom
- `aws ecs update-service ... --enable-execute-command` fails with `"a valid taskRoleArn is not being used"`
- `captal locations push` succeeds but new tasks don't start
- Service stuck in DRAINING after `aws ecs delete-service`

### Diagnose
```bash
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].{Status:status,Running:runningCount,Desired:desiredCount,Deployments:deployments[].rolloutState}'

aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].events[0:5].message'
```

### Fixes

| Cause | Fix |
|---|---|
| `taskRoleArn` missing in `captal.yaml` | Add `ecs.taskRoleArn` to `shared/captal.yaml` (use `configure-aws` to find the value), then re-push |
| Stuck DRAINING | Wait 1-2 min; ECS finishes draining and the next push creates a fresh service |
| Target group not attached to listener | Check ALB rule: `aws elbv2 describe-rules --listener-arn <arn>`. If rule was deleted manually, re-push |
| Health check failing | See section "E" below |

## E. Target group reports `unhealthy` with `Target.Timeout`

### Symptom
- ECS service running but ALB target health is unhealthy
- Browser gets 502/503 from CloudFront when hitting `/<slug>/api/*`

### Diagnose
```bash
TG=$(aws elbv2 describe-target-groups --names captal-<slug> --query 'TargetGroups[0].TargetGroupArn' --output text)
aws elbv2 describe-target-health --target-group-arn $TG \
  --query 'TargetHealthDescriptions[].{State:TargetHealth.State,Reason:TargetHealth.Reason,Description:TargetHealth.Description}'

# Container logs
aws logs tail /ecs/captal-<slug> --since 5m | tail -50
```

### Common causes

| Reason | Fix |
|---|---|
| Migrations failed (rqlite unreachable, missing config key) | Check container logs for `FlywaySqlException` or `ConfigException`. If rqlite issue, see `recover-data`. If config issue, check env vars in task definition. |
| App still starting (slow JVM cold start) | Health check grace period is 180s. Wait. If still timing out, check logs. |
| Health check path wrong | Target group health check should be `/<slug>/api/health`. The CLI sets this; if it's `/health` or similar, the target group is stale — delete and re-push (or update via CLI). |
| Wrong port | Container port should be 8080. Task definition `portMappings.containerPort` and target group `port` must match. |

## F. API returns 500 with `SQLException: No result set returned`

### Symptom
- Endpoints (especially `/api/status`, `/api/locales`, `/api/i18n/*`) return 500
- Logs show `io.rqlite.jdbc.L4Err.generalError` followed by `executeQuery` stack trace

### Cause
JDBC connection from API task to rqlite is stale (the rqlite peer it was talking to disappeared) OR rqlite cluster lost data.

### Fix
See the `recover-data` skill — force-deploy the API service first; if data is genuinely missing, re-run shared/locations push.

## G. CloudFront serves stale content after deploy

### Symptom
- After `captal locations push`, the browser still sees the old version
- Network tab shows `x-cache: Hit from cloudfront`

### Diagnose
```bash
# Confirm an invalidation was created
aws cloudfront list-invalidations --distribution-id <id> --query 'InvalidationList.Items[0:3]'
```

### Fix
- The CLI invalidates `/<slug>/*` automatically on each push. Wait 1-2 min for completion.
- Hard reload in the browser (cache-disable in DevTools network tab).
- If urgent, manually create a wider invalidation: `aws cloudfront create-invalidation --distribution-id <id> --paths "/<slug>/*"`

## H. Welcome shows but no identification questions appear (jumps to advertiser video)

### Symptom
- User clicks "start" on welcome
- Goes directly to advertiser video phase instead of email/profiling/location questions

### Cause
Shared surveys are missing from rqlite (likely data loss). See `recover-data`.

### Fix
1. `captal shared push` (re-inserts surveys + advertisers)
2. Refresh the SPA, restart the flow (use the dev reset button if available, or open in incognito for a fresh session)

## I. "No videos available" on advertiser-video phase

### Symptom
- After identification questions, the SPA reaches advertiser-video phase but says no videos

### Cause
Location's videos are missing from rqlite (data loss) OR `videos/` directory is empty in `locations/<slug>/`.

### Fix
1. Verify `locations/<slug>/videos/` has at least one video YAML
2. `captal locations push <slug>` (re-inserts location data)
3. If still empty, check that the video YAMLs reference an `advertiser` slug that exists in `shared/advertisers/`

## J. Reset button missing on the welcome screen

### Symptom
- The dev reset button (clears the user's session/phase) doesn't render

### Cause
The bundle was built without `ENVIRONMENT=dev` (BuildInfo.isDevMode is false, dead-code-eliminated the button). Mill caches the generated BuildInfo against task inputs, NOT env vars — switching ENVIRONMENT requires manually invalidating the cache.

### Fix (project-side, not CLI)
This is a project release flow concern. Re-bundle with:
```bash
./mill clean client.generatedSources
ENVIRONMENT=dev ./mill client.bundle
# then re-upload bundle to s3://<bucket>/bundle/
```
Then re-run `captal locations push <slug>` so the new bundle propagates to the location.

## When all else fails

Read the project's `AGENTS.md` (in the whitelabel-captal repo) for architectural context. The "Caveats operativos descubiertos en deploy" section enumerates known gotchas with their resolution patterns.
