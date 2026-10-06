---
name: recover-data
description: Use this skill when the portal is missing surveys, videos or translations that were definitely created, or when the API returns 500 with "No result set returned". Triggers on "no veo encuestas", "faltan videos", "no result set", "rqlite perdió datos", "recover data", "data missing", "se perdió la config".
version: 2.0.0
---

# Recover after an rqlite loss

## Two different failures that look the same

| | Stale connection | Actual data loss |
|---|---|---|
| Cause | The rqlite node the API's JDBC connection pointed at disappeared; the driver does not fail over | A node lost its storage and the cluster could not replay it from Raft |
| Looks like | 500 `SQLException: No result set returned` on `/api/status`, `/api/locales`, `/api/i18n/*` | Endpoints answer 200 with nothing in them: no identification questions, "no videos available", raw key names |
| Fix | Force a fresh deployment | Re-enter the content with the tools |

Check the cheap one first — a force-new-deployment costs a minute and rules it out.

## Where the data actually lives

Dev runs **one** rqlite node with an EFS mount at `/rqlite/file` (`rqlite_desired_count = 1`), so its
data survives a redeploy. Prod runs **three** on Fargate ephemeral storage and relies on Raft: a node
redeployed alone is replayed from its peers, but losing quorum — two of three at once — can lose
recent writes permanently.

**There is no file-based replay any more.** The CLI and the YAML tree it pushed are gone, so the
database is the only copy of the content. Nothing can re-apply a directory, and a lost write is lost.
Say that plainly rather than suggesting a push that does not exist.

Operator tokens are the exception. `MCP_ADMIN_TOKEN` seeds the admin row on every startup, so losing
`mcp_tokens` costs the issued operator tokens — reissue them with `create_token` — but never access
itself. That is why the variable is supplied instead of generated.

## Detect

```sh
# Service still running?
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].{Running:runningCount,Desired:desiredCount}'

# Connection errors, or genuinely empty reads?
aws logs tail /ecs/captal-<slug> --since 5m | grep -i 'no result set\|sqlexception'

# Did the cluster churn?
aws logs tail /ecs/<project>-rqlite --since 30m | grep -iE 'leader|elected'
```

## Step 1 — Force fresh connections

```sh
aws ecs update-service --cluster <cluster> --service captal-<slug> --force-new-deployment
```

Wait a minute or two for the task to stabilize, then retry the failing endpoint. If the 500s stop,
that was all it was.

## Step 2 — Find out what is really missing

Call `get_location_readiness` for the slug. It reads the database directly and reports translation
gaps per locale, video and advertiser counts, whether the location has styles and an icon, which
identification surveys exist, and whether UniFi is configured — which is both the diagnosis and the
to-do list. `list_locations`, `list_videos`, `list_advertisers` and `get_i18n_status` narrow it
further.

If the server itself will not start, look for its two startup refusals before blaming the data: it
checks the API's Flyway history is at the version its tools are written against, and it will not run
without `MCP_ADMIN_TOKEN`.

## Step 3 — Re-enter what is gone

Follow the readiness output in the order it gives, with the normal tools:

| Missing | Tool |
|---|---|
| Identification surveys (email / profiling / location) | `create_identification_survey` — deployment-wide, fixes every location at once, needs `identity:write` |
| Translations | `set_translations`, checked with `get_i18n_status` |
| Advertisers | `create_advertiser` |
| Videos | `create_advertiser_video` or `create_promo_video`, then `confirm_video_upload` |
| Styles / icon | `set_location_styles`, `set_location_icon` |
| UniFi credentials | `configure_unifi` |

Videos are the cheap case: the files are in S3, not in rqlite, so a lost video row can be recreated
against the object that is still there. Translations and survey wording are the expensive case —
nothing holds a second copy.

`read_skill('add-location')` is the same sequence written as onboarding.

## Step 4 — Verify

Open the portal with a fresh incognito window and walk Welcome → identification questions → video →
survey → Ready. Then re-run `get_location_readiness` and confirm `nextSteps` is empty.

Still failing: `read_skill('troubleshoot-deployment')`.

## Prevention

- Bounce at most one rqlite node at a time in prod. The module derives
  `deployment_minimum_healthy_percent` from the node count to keep quorum during rolling deploys —
  trust `tofu plan` over this document.
- Giving prod the persistent mount dev already has is an infrastructure change
  (`modules/rqlite` in `whitelabel-captal-infrastructure`), not something this server can do.
- Content now has exactly one copy. Until that changes, treat a prod quorum loss as destructive and
  plan the redeploy around it rather than around a re-push that no longer exists.
