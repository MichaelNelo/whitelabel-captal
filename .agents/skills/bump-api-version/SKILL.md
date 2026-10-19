---
name: bump-api-version
description: Use when releasing a new version of the captal-api image. Covers build, push, tagging, and rolling the new image out to deployed locations through the MCP backoffice's deploy tools. Triggers on "bump api version", "release api", "publish api image", "subir api", "deploy api".
version: 2.0.0
---

# Release a new API image

## When to use

After any change in `core/`, `messages/`, `platform/`, or `api/`. There is a single API image —
`captal-api` — built from an already-assembled jar; there is no separate "provision" image any
more, because there is nothing left to provision. Both migrations and the server run as one `CMD`
inside the same image, so a migration that ships with this release applies the moment a task starts
on it.

## Pre-flight

- Tests pass: `./mill api.test` and `./mill core.test`.
- Working tree is committed (you want the deployed bits to match a git SHA).
- Logged into the target ECR registry: `aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com`.

## Pick a version

Look at what's in ECR for the environment you're releasing to — the repository is `captal-api`,
and its full URI is a terraform output, not a value to hardcode:

```bash
cd terraform
tofu output api_image_repository_url
aws ecr describe-images --repository-name captal-api --region <region> \
  --query 'sort_by(imageDetails[?imageTags!=null],&imagePushedAt)[-1].imageTags[0]'
```

Bump SemVer:
- **Patch** (`v1.5.0 → v1.5.1`): bug fix, no API contract change.
- **Minor** (`v1.5.0 → v1.6.0`): new endpoint / feature, no breaking change.
- **Major** (`v1.5.0 → v2.0.0`): breaking change (cookie name shift, header rename, DB column drop). Coordinate with operators.

`image_tag` (the terraform variable every location's task references) has a validation that
rejects the literal string `"latest"` — pick a real tag, always.

## Steps

### 1. Build and push the image

```bash
TAG=vX.Y.Z
REPO=$(cd terraform && tofu output -raw api_image_repository_url)
mill api.imagePush --registry "${REPO%/*}" --repository "${REPO##*/}" --tag "$TAG"
```

`api.imagePush` builds `docker/api.Dockerfile` around the jar Mill already has, then tags and pushes
it. The registry needs a `docker login` first: `aws ecr get-login-password --region us-east-1 |
docker login --username AWS --password-stdin "${REPO%%/*}"`.

### 2. Point the environment at the new tag

The image a deploy uses comes from the `image_tag` terraform variable, which flows into the MCP
backoffice's own task definition as `API_IMAGE` (`modules/mcp`) — `DeployTargets` reads that
environment variable once at the backoffice's own startup, so the tag change takes effect only
after the backoffice's task restarts with the new value:

```bash
cd terraform
# edit environments/<env>/terraform.tfvars: image_tag = "vX.Y.Z"
tofu plan  -var-file=environments/<env>/terraform.tfvars
tofu apply -var-file=environments/<env>/terraform.tfvars
```

### 3. Migrate

Nothing to do by hand. Every task — the backoffice's and every location's API — runs `Migrate` (or
`McpMigrate`) as part of its own startup `CMD`, guarded by `Migrate.Lock` so concurrent starts don't
race. The first task to boot on the new image applies any migration this release shipped; every
later one finds `0` migrations executed.

### 4. Roll out to deployed locations

There is no bulk "push all" tool. Call `deploy_location` through the MCP backoffice for each slug
that should move to the new image — `list_locations` first if you need the full set:

```
list_locations
deploy_location(slug = "<each slug>")
```

`deploy_location` is idempotent: an already-deployed location gets its service updated to a fresh
task definition revision rather than a second service created, and it registers the task
definition with whatever `API_IMAGE` the backoffice currently has in its environment — which is why
step 2 has to land (and the backoffice's task has to actually restart) before this step.

## Verification

```bash
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].taskDefinition'
aws ecs describe-task-definition --task-definition captal-<slug>:<revision> \
  --query 'taskDefinition.containerDefinitions[0].image'
```

End-to-end:
```bash
curl -i -H 'X-Client-Mac: aa:aa:...' -H 'X-Click-Id: test-001' \
  https://<your-domain>/<slug>/api/status
# → 200 + Set-Cookie: captal_session_<slug>=...
```

## Rollback

Task definition revisions aren't deleted on update, so the fastest path is per-service:

```bash
aws ecs update-service --cluster <cluster> --service captal-<slug> \
  --task-definition captal-<slug>:<old-revision>
```

To make it permanent, revert `image_tag` in the environment's `terraform.tfvars`, `tofu apply`
again so the backoffice's `API_IMAGE` goes back, and call `deploy_location` for every affected slug
so the next registered revision matches.

## Caveats

- **rqlite data loss**: prod rqlite runs on Fargate ephemeral storage with Raft; dev runs one node
  on EFS. See `docs/database.adoc`'s operational-shape section and the `recover-data` MCP skill if
  rqlite itself needs to be rebuilt during this rollout.
- **Cross-version cookie name**: if you change `SessionCookieConfig`'s semantics, old browsers may
  hold a cookie the new server can't read. Plan for a deprecation window.
- **Bundle is separate**: `client.bundle` + `client.publishS3` are a different release, not tied to
  the API image tag. If the client changed too, publish it too — see `docs/client.adoc`.

## Anti-patterns

- ❌ Looking for a `platform.dockerBuild`/`captal-provision` image — it doesn't exist. Migrations
  ship inside `captal-api` itself.
- ❌ Looking for `shared/captal.yaml` or a `captal shared push`/`captal locations push-all` command
  — there is no CLI. The equivalent is a terraform `tofu apply` (step 2) followed by `deploy_location`
  calls (step 4).
- ❌ Tagging `:latest` — rejected outright by the `image_tag` variable's own validation, and there
  is no immutable reference to roll back to if you do.
- ❌ Calling `deploy_location` before the backoffice's own task has picked up the new `API_IMAGE` —
  it will faithfully register a task definition pointing at the *old* tag.
