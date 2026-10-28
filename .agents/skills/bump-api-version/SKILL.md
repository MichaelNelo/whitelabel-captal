---
name: bump-api-version
description: Use when releasing a new version of the captal-api or captal-mcp image. Covers how the version is computed from conventional commits and git tags, the release `mill ci.run` makes (images, terraform, every location rolled, client, tags), and rolling back. Triggers on "bump api version", "release api", "release mcp", "publish api image", "subir api", "deploy api", "nueva versión".
version: 3.0.0
---

# Release a new API or backoffice image

## When to use

After any change in `core/`, `messages/`, `platform/`, `api/` or `mcp/` that should reach a deployed
environment. There is one API image, `captal-api`, and one backoffice image, `captal-mcp`, each
built from an already-assembled jar. Migrations run inside the image's own startup, so a migration
that ships with a release applies the moment a task starts on it.

## The version is computed, not chosen

Each module is versioned by its own git tags, `api/vX.Y.Z` and `mcp/vX.Y.Z`. git-cliff computes the
next one from the conventional commits since the last tag that touch the module, the modules it
depends on, or its Dockerfile — the rules are `api/cliff.toml` and `mcp/cliff.toml`, the paths come
from the build's module graph:

| Commit | Bump |
|---|---|
| `feat: …` | minor |
| `fix: …`, `perf: …` | patch |
| `feat!: …` (any type with `!`) | major |
| `docs`, `refactor`, `tests`, `chore`, `build`, `lint` | none |

So the type of the commit *is* the release decision. A breaking change for operators (cookie name,
header, a dropped column) is a `!`; coordinate it before merging.

See what the next release would be:

```bash
mill show api.releaseVersion   # {"tag": "api/v1.3.0", "previousTag": "api/v1.2.0"}
mill show mcp.releaseVersion
```

`tag` equal to `previousTag` means nothing since the last release moves the version.

## Release

`mill ci.run` is the release, the one CodeBuild runs with `CAPTAL_ENV=<env>`:

1. builds the rqlite, API and backoffice images;
2. applies the configuration with `image_tag` and `mcp_image_tag` set to the versions, pushing each
   image whose version is not in its registry yet — a published version is never pushed again;
3. moves every deployed location onto the API image (a new task definition revision differing only
   in the image) and waits for them to settle;
4. publishes the client and copies it under every location's prefix;
5. tags the commit with every version that moved and pushes the tags.

Nothing to edit: `image_tag` and `mcp_image_tag` are not in any `terraform.tfvars`. Rehearse the
whole release against LocalStack first — it tags nothing there:

```bash
CAPTAL_ENV=local AWS_ENDPOINT_URL=http://localhost:4567 mill ci.run
```

## Migrate

Nothing to do by hand. Every task — the backoffice's and every location's API — runs `Migrate` (or
`McpMigrate`) as part of its own startup, guarded by `Migrate.Lock` so concurrent starts don't race.

## Verification

```bash
aws ecs describe-services --cluster <cluster> --services captal-<slug> \
  --query 'services[0].taskDefinition'
aws ecs describe-task-definition --task-definition captal-<slug>:<revision> \
  --query 'taskDefinition.containerDefinitions[0].image'
git ls-remote --tags origin 'api/v*'
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

That lasts until the next release moves every location again. To make it permanent, revert the
change in a commit (`revert: …` bumps the patch) and release: the fixed code goes out as a new
version, rather than an old version being re-released under its own tag.

## Caveats

- **rqlite data loss**: prod rqlite runs on Fargate ephemeral storage with Raft. See
  `docs/database.adoc`'s operational-shape section and the `recover-data` MCP skill if rqlite itself
  needs to be rebuilt during a rollout.
- **Cross-version cookie name**: if you change `SessionCookieConfig`'s semantics, old browsers may
  hold a cookie the new server can't read. Plan for a deprecation window.
- **`build.mill` does not count** toward a version: most commits touching it change other modules. A
  change there that only alters how an image is built needs a `fix:` in the module, or it is not
  released.

## Anti-patterns

- ❌ Setting `image_tag` or `mcp_image_tag` in a `terraform.tfvars` — the build passes them, and a
  value there would only disagree with it.
- ❌ Creating an `api/v*` or `mcp/v*` tag by hand — the next release counts from it, and an image
  for it was never published.
- ❌ Asking git-cliff for `--bumped-version` to check a version — it ignores the module's paths and
  answers from every commit. `mill show <module>.releaseVersion` is the answer.
- ❌ Tagging `:latest` — rejected by the `image_tag` variable's own validation, and there is no
  immutable reference to roll back to.
