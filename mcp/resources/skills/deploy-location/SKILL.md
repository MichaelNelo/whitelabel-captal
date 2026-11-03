---
name: deploy-location
description: Use this skill for anything that changes whether a location's portal is serving — deploying it, giving it a newer bundle, closing it for a while, bringing it back, or removing it for good. It says which of deploy_location, pause_location, resume_location and destroy_location fits, what each one leaves behind, and how to follow the run each one starts with get_deploy_status. Triggers on "deploy location", "push location", "deploy", "publicar location", "provision location", "publish bundle", "subir el bundle", "go live", "pause location", "pausar", "mantenimiento", "maintenance page", "cerrar temporalmente", "resume location", "reanudar", "reactivar", "destroy location", "eliminar location", "borrar location", "dar de baja", "tear down".
version: 5.1.0
---

# A location's portal: deploying, pausing, removing

| The operator wants | Call |
|---|---|
| A content change to be live | nothing — section 1 |
| A new location's portal up, or a newly published bundle on one | `deploy_location` — section 3 |
| The portal closed for a while, nothing lost | `pause_location`, later `resume_location` — section 4 |
| The location gone from AWS for good | `destroy_location` — section 5 |

All four answer at once with a run and keep working in the background, for minutes. Read "Following
a run" in section 3 before the first one: the answer you get back is the start, not the result.

Three different things get called "deploy" here, and they have three different answers. Work out
which one is meant before doing anything: one needs nothing at all, one is a Mill task, and one is
`deploy_location`.

There is no `captal` CLI and no file provisioning: a `captal …` command names a tool that does not
exist.

## 1. Content: already live, nothing to deploy

Every tool in this server writes straight through. Locations, translations, advertisers, videos,
surveys and questions land in rqlite as the call returns; `set_location_styles` and
`set_location_icon` write to S3 and invalidate the distribution themselves. There is no staging
copy, no push and no apply step.

So the answer to "when does this go live" is "it already is". What is worth doing instead is calling
`get_location_readiness` and clearing what it reports — a location with no translations or no
identification surveys is serving guests raw key names or stranding them on the first screen right
now, not after some future deploy.

## 2. The client bundle: a Mill task, not per location

The SPA bundle is built and published from this repo, once for every location:

```sh
mill client.publishS3 --bucket <assets-bucket> --prefix bundle
```

Optional `--region` (default `us-east-1`). The task sets Content-Type and Cache-Control per file —
a day for the hashed assets, `must-revalidate` with a 24h `s-maxage` for the two HTML files — which
is the part a hand-rolled `aws s3 cp` gets wrong. It does **not** invalidate CloudFront, so do that
yourself after a bundle publish:

```sh
aws cloudfront create-invalidation --distribution-id <id> --paths '/bundle/*'
```

Asset writes through the tools already invalidate their own key; this is only for the bundle.

## 3. A new slug's portal: `deploy_location`

Creating a location row does not create anything in AWS. `create_location` writes to the database;
until a deploy happens, a guest opening `https://…/<slug>/` gets a 403 from the load balancer on
every API call, because no rule matches the path.

`deploy_location(slug)` creates the five things that fixes, in an order that is not arbitrary:

1. the CloudWatch log group, so the task's first lines have somewhere to land
2. the target group, `captal-<slug>` — which is why a slug is capped at 25 characters: AWS caps
   target group names at 32 and the prefix takes seven
3. the ALB listener rule, **before** the service — `createService` with a load balancer attached
   fails with `400 no associated load balancer` while the target group is not reachable through a
   listener
4. the task definition, carrying `LOCATION_SLUG`, `DB_URL` and — when the deployment has a Tailscale
   proxy — `UNIFI_PROXY_URL`, which is how the location reaches its venue's UniFi gateway. If the
   backoffice logged that `UNIFI_PROXY_URL` is unset, a deployed location will stop guests at Ready
   unless its gateway is reachable from AWS directly
5. the ECS service, created the first time and updated after that

It is idempotent. Run on a location that is already deployed it registers a fresh task definition
and rolls the service onto it rather than creating a second one — so it is also how a redeploy is
done, and re-running it after a failure part way through is safe.

The run then waits for the new revision: first for one of its tasks to be running, then for the
service to settle on it, which means its task passed the load balancer's health checks and, on a
redeploy, the previous revision's task was stopped. A redeploy therefore takes several minutes even
when nothing is wrong, because the previous task drains first. Each wait gives up after 15 minutes
and fails the run, naming the log group that says why the container is not coming up; the resources
exist and ECS keeps trying in the meantime. A deploy that succeeded is serving the new revision.

### The bundle copy, which it does first

Before touching AWS, `deploy_location` copies the shared bundle under this location's own prefix —
server-side, so `Content-Encoding` survives on the `.gz` files. Without that the portal page itself
404s and only the API answers, which looks like a broken deploy and is not. It reports
`bundleObjects`, how many it wrote.

**`bundleObjects: 0` is the one answer worth reading.** It means the shared bundle has never been
published, so there was nothing to copy. The resources are all fine; the portal has no page. Publish
it and deploy again:

```sh
mill client.publishS3 --bucket <assets-bucket> --prefix bundle
```

The copy also runs on a redeploy, which is how a location picks up a newly published bundle — so
after publishing, deploy each location that should take the new version.

The location's brand icon is the one key it treats specially, and `iconPreserved` says what it did.
`brand-icon.svg` ships *in* the bundle as a default and `set_location_icon` writes to the same key,
so the copy compares ETags: an icon that is still the default gets refreshed, and one the operator
set is left alone. Nothing to do about it either way — just do not read `iconPreserved: false` as a
lost icon, it is the ordinary answer for a location with no branding of its own.
`custom-styles.css` is not in the bundle and is never touched.

### Following a run

`deploy_location`, `pause_location`, `resume_location` and `destroy_location` each wait minutes on
AWS — for a container, a health check or a CloudFront invalidation — which is longer than a host
keeps one call open. So each one answers at once with a **run**: its `runId`, `status: running`, and
every step it is going to take, all `pending`. The work goes on in the server, and it does not need
this conversation to stay open.

A host that renders MCP Apps also shows the run as a page, `ui://deploy-run`, that ticks each step
over as it happens and tells you, in a message, how the run ended. The operator is watching that;
you do not need to narrate it.

Without the page, follow it yourself with `get_deploy_status(runId)` about every 30 seconds until
`status` is no longer `running`, and tell the operator which step it is on rather than going quiet;
the step names are written to be shown. Without the `runId`, `get_deploy_status(slug)` gives that
location's latest run. The `watch` field of the first answer belongs to the page; leave it alone.

How a run ends:

- **`succeeded`**: the operation's own result is in `deployed`, `paused`, `resumed` or `destroyed`,
  with its own `nextSteps`. Sections 3 to 5 say how to read each one.
- **`failed`**: `error` says why, and the failed step is marked. Every step is idempotent, so once the
  cause is dealt with, calling the same tool again redoes what was done in moments and carries on.
- **`interrupted`**: the server doing the work stopped before it finished, typically a restart.
  Calling the same tool again finishes it.

Only one run per location goes at a time. Calling any of the four while one is running starts
nothing and answers with the run already going; follow that one. Do not rebuild a change out of
individual AWS calls; section 5 says what a half-done change leaves behind.

## 4. Closing a portal for a while: `pause_location` and `resume_location`

For downtime that ends: the venue is closed, being refitted, or its contract is on hold. A pause
deletes nothing and writes nothing to the database.

`pause_location(slug)` does four things, in this order:

1. the maintenance page replaces the location's `index.html` at the edge, and the live page is kept
   aside
2. the CloudFront invalidation is waited out, which takes minutes
3. the ALB rule stops forwarding and answers a fixed `paused` JSON
4. the service is scaled to zero, and the drain is waited out

The order is the point. By the time the API answers `paused`, the edge already serves the
maintenance page, so a guest already inside the portal reads that answer on their next call and goes
to the maintenance page by themselves. `drained: false` means a container was still stopping at the
timeout; the pause is complete regardless, because the rule stopped forwarding first.

While a location is paused the content tools keep working. Translations, surveys, videos, the
stylesheet and the icon can all be edited, and guests see the result once it is back.

`resume_location(slug)` is the reverse: one task again, wait for it to run, forward again, wait for a
healthy target, and only then put the saved page back at the edge. What it can answer:

- **A failed run**, when no task is running after 15 minutes. The location stays paused, with the
  rule still answering `paused` and the edge still serving the maintenance page, because that is the
  safe place for a container that will not start. Read the log group, then call it again.
- **`healthy: false`**: the task runs but the load balancer had not finished checking it when the
  wait ran out. Guests get a 503 until it does.
- **`pageRestored: false`**: no saved page was found. Expected for a location that was never paused,
  and harmless, since resuming one is safe.

Resume puts back the page that was live before the pause, not the current bundle. `deploy_location`
is the other way out of a pause: it scales the service up, forwards again and copies the current
bundle, so it is the one to use when a bundle was published while the location was paused.

## 5. Removing a location for good: `destroy_location`

For a venue that is leaving. Not for downtime: a pause deletes nothing, and this deletes what cannot
be recovered.

`destroy_location(slug, confirm)` needs the slug typed again in `confirm`. Anything else is refused
before it does anything. It needs the `infra:destroy` scope, which `infra:deploy` does not carry.

Before tearing anything down it asks the operator whether to also delete the location's S3 objects:
its bundle copy, stylesheet, icon and every uploaded video file, saying how many objects and how many
of them are videos. The question comes in the call itself, before any run starts. **If no answer
comes back, they are kept**, and that includes a client that cannot ask the question at all. Then
the run scales the service to zero, waits for the drain, and deletes the service, the listener
rules, the target group and the task definition revisions. `drained: false` means the teardown went
ahead while a container was still stopping.

What survives, every time:

- **The database.** The location row, its translations, surveys, videos and tokens are all still
  there, and `list_locations` still lists it. No tool removes a location from the database.
- **The log group**, because it holds why the location was torn down. Deleting it is by hand.

What follows from the S3 answer:

- **Objects kept** (`assetsKept: true`): `deploy_location` stands the portal back up on top of them,
  with nothing else to do.
- **Objects deleted**: `deploy_location` still brings the portal back, but the stylesheet and icon
  have to be set again, and **every video has to be uploaded again**. Their rows survive pointing at
  files that are gone, and `list_pending_uploads` will not find them, because they are still marked
  active. Retire them with `set_video_active` or create them again.

It is idempotent: a teardown that follows a failed one reports what it found rather than failing, so
an interrupted destroy is finished by calling it again. Never finish one by hand with individual AWS
calls: a listener rule left pointing at a target group with no service answers 503 instead of
falling through.

## Shared infrastructure is not here

The cluster, the ALB, the roles, the distribution and the rqlite service are not created by any
tool. They live under `terraform/` and are applied with OpenTofu — see
`read_skill('configure-aws')`. `deploy_location` reads them from its environment (`ECS_CLUSTER`, the
subnets, the security group, the listener ARN, the two roles, the image and `DB_URL`).

If `deploy_location` is not in the tool list at all, this server was started without that
configuration: the tool is registered only when it is present, so that it cannot exist and fail on
every call. The same goes for the pause, resume and destroy tools, which are registered with it.
That is a deployment question for whoever runs the server, not something to work around.

## Clearing a stale database connection

Forcing fresh tasks without changing anything is how a stale JDBC connection to rqlite is cleared:

```sh
aws ecs update-service --cluster <cluster> --service captal-<slug> --force-new-deployment
```

`deploy_location` has the same effect and goes through the tool, so prefer it unless you specifically
want no new task definition. Content survives either way, since it lives in the database rather than
the image. See `read_skill('recover-data')` for when that is the right move, and
`read_skill('troubleshoot-deployment')` for everything else.
