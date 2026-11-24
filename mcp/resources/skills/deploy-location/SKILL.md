---
name: deploy-location
description: Use this skill for anything that changes whether a location's portal is serving — deploying it, giving it a newer bundle, closing it for a while, bringing it back, or removing it for good. It says which of deploy_location, pause_location, resume_location and destroy_location fits, what each one leaves behind, and how to follow the run each one starts with get_deploy_status. Triggers on "deploy location", "push location", "deploy", "publicar location", "provision location", "publish bundle", "subir el bundle", "go live", "pause location", "pausar", "mantenimiento", "maintenance page", "cerrar temporalmente", "resume location", "reanudar", "reactivar", "destroy location", "eliminar location", "borrar location", "dar de baja", "tear down".
version: 6.1.0
---

# A location's portal: deploying, pausing, removing

| The operator wants | Call |
|---|---|
| A content change to be live | nothing — section 1 |
| A newer bundle or image on the locations | nothing here: the release does it — section 2 |
| A new location's portal up, or a destroyed one back | `deploy_location` — section 3 |
| The portal closed for a while, nothing lost | `pause_location`, later `resume_location` — section 4 |
| The location gone from AWS for good | `destroy_location` — section 5 |

All four answer at once with a run and keep working in the background, for minutes. Read "Following
a run" in section 3 before the first one: the answer you get back is the start, not the result.

Three different things get called "deploy" here, and they have three different answers. Work out
which one is meant before doing anything: one needs nothing at all, one is the release, and one is
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

## 2. A newer bundle or image: the release, not a tool

The portal's bundle and the API's image reach every location through the release, `ci.run`, which
a developer runs from the repository: it moves every location's service onto the new image, a
paused one included, and copies the new bundle to every location, a paused one's into the page its
resume puts back. Nothing in this server does it, and `deploy_location` does not either — it refuses
a location already deployed. If the operator wants the latest version on a location, the answer is
the next release.

## 3. A new slug's portal: `deploy_location`

Creating a location row does not create anything in AWS. `create_location` writes to the database;
until a deploy happens, a guest opening `https://…/<slug>/` gets a 403 from the load balancer on
every API call, because no rule matches the path.

`deploy_location` refuses, before it starts anything, while no version of the terms of service
exists: a portal asks every guest to accept them before the flow begins, so without one it could
start nobody. `get_terms_of_service` says whether there is one; `create_terms_version` publishes it.
It refuses too while the location has no access point, since a deployed location with none turns
every guest away, or is configured with one another location has taken, deploying or deployed:
configure one with `configure_unifi`, or pause the other location first. `resume_location` asks the
same. A deploy or a resume takes the location's access points as it starts — two started at the same
moment through one AP, the second is refused — and a pause or a destroy lets them go once it
succeeds. `get_unifi_config` says which location took each.

`deploy_location(slug)` creates the five things that fixes, in an order that is not arbitrary:

1. the CloudWatch log group, so the task's first lines have somewhere to land
2. the target group, `captal-<slug>` — or, for a slug too long for the 32 characters AWS allows a
   target group's name, `captal-` with the slug's first 16 characters and 8 of its hash
3. the ALB listener rule, **before** the service — `createService` with a load balancer attached
   fails with `400 no associated load balancer` while the target group is not reachable through a
   listener
4. the task definition, carrying `LOCATION_SLUG`, `DB_URL` and — when the deployment has a Tailscale
   proxy — `UNIFI_PROXY_URL`, which is how the location reaches its venue's UniFi gateway. If the
   backoffice logged that `UNIFI_PROXY_URL` is unset, a deployed location will stop guests at Ready
   unless its gateway is reachable from AWS directly
5. the ECS service, created the first time and updated after that

It is for a location that is created, or destroyed: a deploy stands one back up. One already
deployed is refused, since there is nothing for a deploy to do there (section 2); one paused is
brought back with `resume_location`.

The run then waits for the new revision: first for one of its tasks to be running, then for the
service to settle on it, which means its task passed the load balancer's health checks. Each wait
gives up after 15 minutes and fails the run, naming the log group that says why the container is not
coming up; the resources exist and ECS keeps trying in the meantime. A deploy that succeeded is
serving.

### The bundle copy, which it does first

Before touching AWS, `deploy_location` copies the shared bundle under this location's own prefix —
server-side, so `Content-Encoding` survives on the `.gz` files. Without that the portal page itself
404s and only the API answers, which looks like a broken deploy and is not. The step's note says how
many objects it wrote.

**A note saying no bundle objects is the one worth reading.** It means the shared bundle has never
been published, so there was nothing to copy. The resources are all fine; the portal has no page
until a release publishes it.

The location's brand icon is the one key the copy treats specially: `brand-icon.svg` ships *in* the
bundle as a default and `set_location_icon` writes to the same key, so the copy compares ETags — an
icon that is still the default gets refreshed, and one the operator set is left alone.
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

- **`succeeded`**: every step is done, each with a note of what it found where there is one — a
  revision, an invalidation, a count, "still stopping when the wait ran out" — and `nextSteps` says
  what is left. Sections 3 to 5 say which notes are worth reading. The location is recorded as
  deployed, paused or destroyed from then on, which is what the access point rules read.
- **`failed`**: `error` says why, and the failed step is marked. The location stays recorded as it
  was, half changed in AWS.
- **`interrupted`**: the server doing the work stopped before it finished, typically a restart.

A failed or interrupted run is finished, not redone. Once the cause is dealt with, call the same tool
again: it asks the operator whether to finish that run first, and yes runs only the steps it left —
each of which can run twice with the same result. Any other operation asked of the location meets
the same question, except a destroy, which removes whatever is there.

Only one run per location goes at a time. Calling any of the four while one is running starts
nothing, and the tool asks the operator whether to follow that one instead. Do not rebuild a change
out of individual AWS calls; section 5 says what a half-done change leaves behind.

## 4. Closing a portal for a while: `pause_location` and `resume_location`

For downtime that ends: the venue is closed, being refitted, or its contract is on hold. A pause
deletes nothing, and writes nothing to the database but the location's state, paused, once it
succeeds.

`pause_location(slug)` does four things, in this order:

1. the maintenance page replaces the location's `index.html` at the edge, and the live page is kept
   aside
2. the CloudFront invalidation is waited out, which takes minutes
3. the ALB rule stops forwarding and answers a fixed `paused` JSON
4. the service is scaled to zero, and the drain is waited out

The order is the point. By the time the API answers `paused`, the edge already serves the
maintenance page, so a guest already inside the portal reads that answer on their next call and goes
to the maintenance page by themselves. A note on the last step saying it was still stopping means a
container had not stopped at the timeout; the pause is complete regardless, because the rule stopped
forwarding first.

While a location is paused the content tools keep working. Translations, surveys, videos, the
stylesheet and the icon can all be edited, and guests see the result once it is back.

`resume_location(slug)`, for a paused location, is the reverse: one task again, wait for it to run,
forward again, wait for a healthy target, and only then put the saved page back at the edge. What it
can answer:

- **A failed run**, when no task is running after 15 minutes. The location stays paused, with the
  rule still answering `paused` and the edge still serving the maintenance page, because that is the
  safe place for a container that will not start. Read the log group, then call it again and finish
  the run.
- **A note on the health step saying it was not yet healthy**: the task runs but the load balancer
  had not finished checking it when the wait ran out. Guests get a 503 until it does.
- **A note on the page step saying no saved page was found**: the edge serves whatever was at the
  location's page.

Resume puts back the page the pause kept, which a release published while the location was paused
has already replaced with the new bundle's.

## 5. Removing a location for good: `destroy_location`

For a venue that is leaving. Not for downtime: a pause deletes nothing, and this deletes what cannot
be recovered.

`destroy_location(slug, confirm)` needs the slug typed again in `confirm`. Anything else is refused
before it does anything. It needs the `infra:destroy` scope, which `infra:deploy` does not carry. It
goes from any state — a location deployed, paused, or one whose deploy failed part way and left
resources standing — and over a run left part way, which it removes along with the rest.

Before tearing anything down it asks the operator whether to also delete the location's S3 objects:
its bundle copy, stylesheet, icon and every uploaded video file, saying how many objects and how many
of them are videos. The question comes in the call itself, before any run starts. **If no answer
comes back, they are kept**, and that includes a client that cannot ask the question at all. Then
the run scales the service to zero, waits for the drain, and deletes the service, the listener
rules, the target group and the task definition revisions; the teardown step's note says what it
found. A note on the drain step saying it was still stopping means the teardown went ahead while a
container was still stopping.

What survives, every time:

- **The database.** The location row, its translations, surveys, videos and tokens are all still
  there, and `list_locations` still lists it. No tool removes a location from the database.
- **The log group**, because it holds why the location was torn down. Deleting it is by hand.

What follows from the S3 answer:

- **Objects kept** (the last step keeps them): `deploy_location` stands the portal back up on top
  of them, with nothing else to do.
- **Objects deleted**: `deploy_location` still brings the portal back, but the stylesheet and icon
  have to be set again, and **every video has to be uploaded again**. Their rows survive pointing at
  files that are gone, and `list_pending_uploads` will not find them, because they are still marked
  active. Retire them with `set_video_active` or create them again.

A teardown that follows another reports what it found rather than failing, so an interrupted destroy
is finished by calling it again. Never finish one by hand with individual AWS calls: a listener rule
left pointing at a target group with no service answers 503 instead of falling through.

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
