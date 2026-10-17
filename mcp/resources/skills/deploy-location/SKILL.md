---
name: deploy-location
description: Use this skill when asked to deploy, push or publish a location — it separates the two meanings that need nothing from the one that calls deploy_location, and explains what that tool creates, what it reports, and what it still leaves to someone else. Triggers on "deploy location", "push location", "deploy", "publicar location", "provision location", "publish bundle", "subir el bundle", "go live".
version: 3.1.0
---

# Deploying a location

Three different things get called "deploy" here, and they have three different answers. Work out
which one is meant before doing anything: one needs nothing at all, one is a Mill task, and one is
`deploy_location`.

There is no `captal` CLI. It was deleted along with the file-provisioning engine, so anything that
remembers a `captal …` command is remembering a tool that no longer exists.

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
./mill client.publishS3 --bucket <assets-bucket> --prefix bundle
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
4. the task definition, carrying `LOCATION_SLUG` and `DB_URL`
5. the ECS service, created the first time and updated after that

It is idempotent. Run on a location that is already deployed it registers a fresh task definition
and rolls the service onto it rather than creating a second one — so it is also how a redeploy is
done, and re-running it after a failure part way through is safe.

It does **not** wait for the container to pass its health checks. A successful call means the
resources exist, not that a guest can load the portal. Health checks have a 180s grace period; watch
the log group it reports if the service does not come up.

### The bundle copy, which it does first

Before touching AWS, `deploy_location` copies the shared bundle under this location's own prefix —
server-side, so `Content-Encoding` survives on the `.gz` files. Without that the portal page itself
404s and only the API answers, which looks like a broken deploy and is not. It reports
`bundleObjects`, how many it wrote.

**`bundleObjects: 0` is the one answer worth reading.** It means the shared bundle has never been
published, so there was nothing to copy. The resources are all fine; the portal has no page. Publish
it and deploy again:

```sh
./mill client.publishS3 --bucket <assets-bucket> --prefix bundle
```

The copy also runs on a redeploy, which is how a location picks up a newly published bundle — so
after publishing, deploy each location that should take the new version.

The location's brand icon is the one key it treats specially, and `iconPreserved` says what it did.
`brand-icon.svg` ships *in* the bundle as a default and `set_location_icon` writes to the same key,
so the copy compares ETags: an icon that is still the default gets refreshed, and one the operator
set is left alone. Nothing to do about it either way — just do not read `iconPreserved: false` as a
lost icon, it is the ordinary answer for a location with no branding of its own.
`custom-styles.css.gz` is not in the bundle and is never touched.

### How the call comes back, and why that is not yours to choose

`deploy_location` may answer in one of two ways:

- **As a task** — the call returns a handle at once, the client polls `tasks/get` until a terminal
  status and then reads the result. The work survives a disconnect.
- **In the call itself**, with the result directly.

Which one happens is decided by the **server**, from whether the request declared the
`io.modelcontextprotocol/tasks` extension. That declaration is written by the application the
conversation is happening in — Claude Desktop, an IDE, a script — when it builds the request. There
is no separate MCP client to install or switch on: it is part of that application, and the model
reading this is a different layer of the same program, downstream of the decision.

So this is not a setting on this server, not an argument to pass, and not something to raise with an
operator as though they could toggle it. Do not decline to deploy because the answer came back in
the call rather than as a task.

What *is* actionable: when the call answers synchronously the tool says so in its `nextSteps`. If a
deploy ever ends in a transport timeout rather than an error, that is **not** evidence it failed —
every AWS call may have succeeded with only the reply lost. Call `deploy_location` again and compare
what it reports; every step is idempotent, and `created: false` means the service was already there.

The policy is `WhenSupported` deliberately, so a client that cannot poll still gets a working
deploy. It matters more for what is coming than for today: this call makes five AWS requests and
waits for none of them, so a timeout is unlikely. A deploy that waited for the service to become
healthy would have no sensible synchronous form at all.

## Shared infrastructure is not here

The cluster, the ALB, the roles, the distribution and the rqlite service are not created by any
tool. They live in `whitelabel-captal-infrastructure` and are applied with OpenTofu — see
`read_skill('configure-aws')`. `deploy_location` reads them from its environment (`ECS_CLUSTER`, the
subnets, the security group, the listener ARN, the two roles, the image and `DB_URL`).

If `deploy_location` is not in the tool list at all, this server was started without that
configuration: the tool is registered only when it is present, so that it cannot exist and fail on
every call. That is a deployment question for whoever runs the server, not something to work around.

Tearing a location down has no tool either. Say so plainly rather than improvising one out of
`aws ecs delete-service` — a half-removed location leaves a listener rule pointing at a target group
with no service, which answers 503 instead of falling through.

## Clearing a stale database connection

Forcing fresh tasks without changing anything is how a stale JDBC connection to rqlite is cleared:

```sh
aws ecs update-service --cluster <cluster> --service captal-<slug> --force-new-deployment
```

`deploy_location` has the same effect and goes through the tool, so prefer it unless you specifically
want no new task definition. Content survives either way, since it lives in the database rather than
the image. See `read_skill('recover-data')` for when that is the right move, and
`read_skill('troubleshoot-deployment')` for everything else.
