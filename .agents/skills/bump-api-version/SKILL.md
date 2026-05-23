---
name: bump-api-version
description: Use when releasing a new version of the captal-api / captal-provision base images. Covers build, push, tagging, example config bump, and rolling out to all locations. Triggers on "bump api version", "release api", "publish api image", "subir api", "deploy api".
version: 1.0.0
---

# Release a new API base version

## When to use

After any change in `core/`, `endpoints/`, `infra/`, `api/`, `provision/`, or `Dockerfile.api`/`Dockerfile.provision`. The two ECR base images (`captal-api-dev`, `captal-provision-dev`) are versioned together — even if only one module changed, push both with the same tag for traceability.

## Pre-flight

- Tests pass: `./mill api.test` (currently 47 suites).
- Working tree is committed (you want the deployed bits to match a git SHA).
- Logged into ECR: `aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 460486036288.dkr.ecr.us-east-1.amazonaws.com`.

## Pick a version

Look at what's in ECR:

```bash
aws ecr describe-images --repository-name captal-api-dev --region us-east-1 \
  --query 'sort_by(imageDetails[?imageTags!=null],&imagePushedAt)[-1].imageTags[0]'
```

Bump SemVer:
- **Patch** (`v1.5.0 → v1.5.1`): bug fix, no API contract change.
- **Minor** (`v1.5.0 → v1.6.0`): new endpoint / feature, no breaking change.
- **Major** (`v1.5.0 → v2.0.0`): breaking change (cookie name shift, header rename, DB column drop). Coordinate with operators.

## Steps

### 1. Build + push the API image

```bash
TAG=vX.Y.Z
./mill api.dockerBuild --repoUri 460486036288.dkr.ecr.us-east-1.amazonaws.com/captal-api-dev --tag $TAG
./mill api.dockerPush  --repoUri 460486036288.dkr.ecr.us-east-1.amazonaws.com/captal-api-dev --tag $TAG
```

### 2. Build + push the Provision image (same tag)

```bash
./mill infra.dockerBuild --repoUri 460486036288.dkr.ecr.us-east-1.amazonaws.com/captal-provision-dev --tag $TAG
./mill infra.dockerPush  --repoUri 460486036288.dkr.ecr.us-east-1.amazonaws.com/captal-provision-dev --tag $TAG
```

### 3. Bump the project's `shared/captal.yaml`

In the operator's captal project directory:

```yaml
images:
  api: "460486036288.dkr.ecr.us-east-1.amazonaws.com/captal-api-dev:vX.Y.Z"
  provision: "460486036288.dkr.ecr.us-east-1.amazonaws.com/captal-provision-dev:vX.Y.Z"
```

Commit this change.

### 4. Migrate the DB if needed

If this release includes a new Flyway migration (V*), run `captal shared push` to fire an ephemeral provision task that applies it:

```bash
captal shared push
```

This also re-provisions surveys/advertisers — idempotent via content-hash manifest.

### 5. Roll out to all locations

```bash
captal locations push-all
```

Iterates over every `locations/<slug>/` directory, rebuilds the derived image FROM the new API base, registers a new task definition revision, updates the ECS service (with the new task def), and invalidates `/<slug>/*` in CloudFront. Sequential (one slug at a time).

## Update AGENTS.md

Add a line in the "Versiones publicadas en ECR / S3" tabla:

```
- **API**: ... → `v1.5.0` → `vX.Y.Z` (<one-line summary of what changed>)
- **Provision**: ... → `vX.Y.Z` (same release tag)
```

## Verification

After the push-all completes:

```bash
# Check actual deployed images per service:
for slug in $(aws elbv2 describe-target-groups --query 'TargetGroups[?starts_with(TargetGroupName, `captal-`)].TargetGroupName' --output text); do
  svc=${slug}
  aws ecs describe-services --cluster captal-dev --services $svc \
    --query 'services[0].taskDefinition' --output text
done
```

Each task definition revision should reference the new tag (verify by inspecting the task def directly):

```bash
aws ecs describe-task-definition --task-definition captal-cafe-centro:NN \
  --query 'taskDefinition.containerDefinitions[0].image'
```

End-to-end:
```bash
curl -i -H 'X-Client-Mac: aa:aa:...' -H 'X-Click-Id: test-001' \
  https://production.captal.centauroads.com/<slug>/api/status
# → 200 + Set-Cookie: captal_session_<slug>=...
```

## Rollback

If something breaks, re-deploy the previous tag:

1. Edit `shared/captal.yaml` back to the previous `vX.Y.(Z-1)`.
2. `captal locations push-all` — same flow, derived images get rebuilt FROM the old base.

The previous task definition revisions stay in ECS (not deleted on update), so you could also `aws ecs update-service --task-definition captal-<slug>:<old-revision>` for an instant rollback per service, then later push-all to make it permanent.

## Caveats

- **rqlite data loss**: Fargate ephemeral storage. If rqlite gets redeployed during this rollout (unlikely if you don't touch the `rqlite` module), follow `recover-data`.
- **Cross-version cookie name**: if you change `SessionCookieConfig` semantics, old browsers may hold a cookie with the new server can't read. Plan for a deprecation window.
- **Bundle is separate**: `client.bundle` + `client.publishS3` are NOT part of this flow. The client bundle is per-S3 deploy, not per-API version. If the client changed too, publish the bundle separately first (`./mill client.publishS3 --bucket captal-dev-assets --prefix bundle`).

## Anti-patterns

- ❌ Pushing API and Provision with different tags — drift between provisioner and runtime in subtle ways.
- ❌ Tagging `:latest` only — no immutable reference for rollback. Always tag with a SemVer.
- ❌ Skipping `captal shared push` after a migration — locations re-run Flyway on each API task start so it works eventually, but the ephemeral task is the canonical "DDL applied here" event in CloudWatch.
