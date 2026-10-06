---
name: deploy-location
description: Use this skill when asked to deploy, push or publish a location — it explains what no longer needs a deploy at all, and which part has no implementation yet. Triggers on "deploy location", "push location", "deploy", "publicar location", "provision location", "publish bundle", "subir el bundle", "go live".
version: 2.0.0
---

# Deploying a location

**There is no `deploy_location` tool and no `captal` CLI.** If a request sounds like "deploy this
location", find out first which of the three things below it actually means — two of them need
nothing, and the third has no implementation.

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

The SPA bundle is shared by every location and published from this repo:

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

## 3. Standing up a new slug's portal service: not implemented

Creating a location row does not create anything in AWS. A brand-new slug needs a per-location copy
of the bundle under `<slug>/`, an ECS service, a target group (`captal-<slug>`, which is why the slug
is capped at 25 characters) and an ALB listener rule. **Nothing does this today:**

- `captal locations push` did it, and the CLI was deleted along with the file-provisioning engine.
- The infrastructure repo does not cover it either. `modules/ecs` creates the cluster, log groups,
  security group and roles; `modules/alb` creates one target group named `<project>-<environment>`
  plus the portal-dispatcher rule. There is no per-location service, target group or listener rule
  anywhere in it, and the `captal-locations` ECR repo it still defines is left over from the CLI.

Say so plainly rather than improvising. The two honest answers are:

1. **Add it to the infrastructure repo** (`whitelabel-captal-infrastructure`), where the state and
   the plan live, and where `tofu plan` can tell an operator what exists. See
   `read_skill('configure-aws')`.
2. **Do it by hand with the AWS CLI** for one slug, knowing it will be invisible to Terraform state
   and will drift.

Do not claim a tool, a Mill task or a CLI command that would do this. There is none.

## Redeploying an existing location

Forcing fresh tasks is the one routine operation that still works as it always did, and it is how a
stale JDBC connection to rqlite is cleared:

```sh
aws ecs update-service --cluster <cluster> --service captal-<slug> --force-new-deployment
```

Content survives it, since the content lives in the database rather than in the image. See
`read_skill('recover-data')` for when that is the right move, and
`read_skill('troubleshoot-deployment')` for everything else.
