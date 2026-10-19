---
name: troubleshoot-deployment
description: Symptom to cause to fix for a portal that is misbehaving — blank page, 403s, 500s, unhealthy targets, stale content, missing questions or videos, guests stuck at Ready with no internet. Triggers on "no funciona", "broken", "error", "503", "502", "500", "404", "403", "unhealthy", "stuck", "drain", "no carga", "troubleshoot", "diagnose", "debug", "sin internet", "no autoriza".
version: 2.0.0
---

# Troubleshoot a portal

A symptom → cause → fix cheat sheet. Two notes before using it:

- **Content problems are not deploy problems.** Every tool writes immediately, so a missing survey,
  translation or video is missing data, not an unapplied deploy. Start with
  `get_location_readiness` for the slug — it answers most of sections H through M in one call.
- **There is no `captal` CLI and no deploy tool.** Where an AWS-side fix is needed, it is either an
  `aws` command or a change under `terraform/`. See
  `read_skill('deploy-location')` for what has no implementation at all.

## A. SPA loads HTML but assets 404 or 403

Console shows the JS or CSS failing; the page is blank or unstyled.

```sh
curl -sI https://<domain>/<slug>/main.js
```

- **403** — bucket policy. See B.
- **404 of files that should exist** — the bundle is not under that prefix. The shared bundle is
  published with `./mill client.publishS3 --bucket <assets-bucket> --prefix bundle`; the per-location
  copy under `<slug>/` was the CLI's job and **nothing does it today** (see
  `read_skill('deploy-location')`).
- **Wrong or missing Content-Type** — something uploaded with plain `aws s3 cp`. The Mill task sets
  the metadata; use it rather than reconstructing the flags.

## B. 403 instead of 404 on a missing key

Typically `custom-styles.css.gz`, whose `<link onerror>` only fires reliably on a 404.

S3 with a private bucket and OAC answers 403 for a missing key unless the principal has
`s3:ListBucket`, and CloudFront forwards it. The policy in `modules/cloudfront` already grants both
`s3:GetObject` on `/*` and `s3:ListBucket` on the bucket for exactly this reason — so a 403 here
means that has drifted. Run `tofu plan` under `terraform/` rather than patching the policy by
hand.

## C. Assets requested relative to an SPA route

Reloading `/cafe-centro/video/` makes the browser ask for `/cafe-centro/video/main.js`.

Relative URLs in `index.html` resolve against `window.location.pathname`, not the SPA root. The
bundle injects a `<base href="/<slug>/">` from an inline script at the top of `<head>`; seeing this
means the deployed bundle predates that. Re-bundle and republish (section A), then invalidate.

## D. ECS service stuck, or UpdateService refused

```sh
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].{Status:status,Running:runningCount,Desired:desiredCount,Deployments:deployments[].rolloutState}'
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].events[0:5].message'
```

| Cause | Fix |
|---|---|
| `"a valid taskRoleArn is not being used"` | The service was created without a task role. The roles live in `modules/ecs`; there is no `captal.yaml` to add one to any more |
| Stuck `DRAINING` | Wait a minute or two; ECS finishes on its own |
| Listener rule or target group missing | Nothing recreates them — see `read_skill('deploy-location')` before promising a fix |
| Targets unhealthy | Section E |

## E. Target group unhealthy with `Target.Timeout`

```sh
TG=$(aws elbv2 describe-target-groups --names captal-<slug> \
  --query 'TargetGroups[0].TargetGroupArn' --output text)
aws elbv2 describe-target-health --target-group-arn $TG \
  --query 'TargetHealthDescriptions[].{State:TargetHealth.State,Reason:TargetHealth.Reason,Description:TargetHealth.Description}'
aws logs tail /ecs/captal-<slug> --since 5m | tail -50
```

| Reason | Fix |
|---|---|
| `FlywaySqlException` / rqlite unreachable | See `read_skill('recover-data')` |
| Config missing in the task definition | Check the env vars; `read_skill('configure-aws')` lists what each service needs |
| Slow JVM cold start | The grace period is 180s. Wait, then read the logs |
| Health check path wrong | It should be `/<slug>/api/health`. `health_check_path` is a variable of `modules/alb`, and no tool rewrites a live target group |
| Wrong port | Container port 8080; the task definition's `portMappings.containerPort` and the target group `port` must agree |

## F. 500 with `SQLException: No result set returned`

A stale JDBC connection to rqlite, or genuine data loss. Force-deploy first, then diagnose — the full
procedure is `read_skill('recover-data')`.

```sh
aws ecs update-service --cluster <cluster> --service captal-<slug> --force-new-deployment
```

## G. CloudFront serves stale content

`x-cache: Hit from cloudfront` after a change.

- `set_location_styles` and `set_location_icon` invalidate their own key already. If a stylesheet
  edit is not visible, suspect the browser cache before the edge, and hard-reload.
- `client.publishS3` does **not** invalidate. After a bundle publish:
  `aws cloudfront create-invalidation --distribution-id <id> --paths '/bundle/*'`.
- Confirm one ran: `aws cloudfront list-invalidations --distribution-id <id> --query 'InvalidationList.Items[0:3]'`.

## H. Welcome shows, then jumps straight to the video

No identification questions. The three deployment-wide surveys — email, profiling, location — do not
exist; a guest is never identified. `get_location_readiness` reports them as
`missingIdentificationSurveys`. Fix with `create_identification_survey` (needs `identity:write`); one
call fixes every location at once. Then restart the flow in a fresh incognito window.

## I. "No videos available"

The location has nothing in rotation. `list_videos` for what it has, `list_pending_uploads` for
videos whose file never arrived — those are registered, invisible to guests, and finished with
`confirm_video_upload` or `resume_video_upload`, which is cheaper than creating another. If a video
is active but nothing plays, check that its advertiser is still active with `list_advertisers`:
retiring an advertiser takes its videos out of the rotation.

## J. Guests reach Ready but have no internet

The flow completed and the authorization never happened. `get_unifi_config` for the slug:
`configured` is false unless host, site id and token are all stored. Fix with `configure_unifi`,
which opens a form — it takes no token as an argument on purpose. `unifiConfigured` in
`list_locations` and `get_location_readiness` reports the same thing.

## K. The UCG redirect gives 404 "No location registered for AP"

The portal dispatcher Lambda resolves the slug by AP MAC:
`SELECT slug, unifi_redirect_url FROM locations WHERE LOWER(unifi_ap_mac) = ?`. A location with no
`apMac` stored, or one that does not match the AP the guest hit, has no row to find. Set it through
`configure_unifi`; `get_unifi_config` shows what is stored. Two locations sharing an AP MAC is the
other way to get this wrong.

## L. The portal renders key names or `[some.key]` to guests

No translations for that locale, or one was retired. `get_i18n_status` for the gaps,
`set_translations` to fill them. A literal `[key]` specifically means `remove_translation` soft
deleted it — set it again. Read `read_skill('edit-i18n')`.

## M. The dev reset button is missing from the welcome screen

The bundle was built without `ENVIRONMENT=dev`, so `BuildInfo.isDevMode` was false and the button was
eliminated as dead code. Mill caches the generated BuildInfo against task inputs, not env vars, so
the cache has to be invalidated by hand:

```sh
./mill clean client.generatedSources
ENVIRONMENT=dev ./mill client.bundle
```

Then republish and invalidate (sections A and G).

## When all else fails

`AGENTS.md` in this repo has the architectural context, and its "Caveats operativos descubiertos en
deploy" section enumerates known gotchas. For anything about what is actually deployed, `tofu plan`
under `terraform/` outranks every document.
