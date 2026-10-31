---
name: bump-rqlite-image
description: Use when changing the rqlite image — its Dockerfile, its entrypoint, or the engine version. Covers the three versions that move together, the tag the tfvars owns, and the build tasks. Triggers on "rqlite image", "rqlite entrypoint", "bump rqlite", "rqlite version", "subir rqlite".
version: 1.0.0
---

# Change the rqlite image

The database runs a custom image, not the official one: the pinned engine plus this repository's
entrypoint, which discovers the node's own address and writes the backup configuration.

```
docker/rqlite.Dockerfile       FROM rqlite/rqlite:10.6.1 + the entrypoint
docker/rqlite-entrypoint.sh    IP discovery, state migration, auto-backup/restore config
```

## Three versions move together

| Where | Now | What it is |
|---|---|---|
| `docker/rqlite.Dockerfile`, `FROM` | `10.6.1` | the engine the cluster runs |
| `build.mill`, `rqliteVersion` | `10.6.1` | the plain container every suite talks to |
| `build.mill`, `io.rqlite:rqlite-jdbc` | `10.5.2.1` | the driver |

Moving one means moving the others. The driver tracks the server: since rqlite v10.6.0 a failed
statement carries a structured error code, and only driver 10.5.2.1 can read it. On an older driver a
statement the database refuses raises **nothing** — which is the whole reason every write goes
through `Writes.expectOne`.

These drifted once already: the suite ran 8.36.2 while the Dockerfile said `:latest` and had reached
10.6.1 — two major versions apart, with the tests on the side nothing deployed.

## The tag is owned by the tfvars

`mill rqlite.image` does **not** choose a tag. The build reads `rqlite_image_tag` out of
`terraform/environments/<env>/terraform.tfvars`, so the tag pushed and the tag the task definition
pulls cannot drift. Bump it there:

```hcl
rqlite_image_tag = "1.0.8"
```

Bump it on **every** entrypoint or Dockerfile change, even a one-line one. The tag is what changes
the task definition revision; without a new revision ECS has nothing to roll and the cluster keeps
running the old image.

Both `local` and `prod` set it. A new revision replaces every node, and the nodes keep no data of
their own: what survives is what the S3 backup restores. Before applying one to `prod`, check the
backup bucket (`captal-prod-rqlite-backup`) holds a recent version.

## The apply pushes it

`mill mcp.dev`, `mill mcp.test` and `mill ci.run` build the image as `captal-rqlite:latest`, and the
apply pushes it when `rqlite_image_tag` changed (`terraform/modules/images`): after the repository
exists and before the task definition, which names the image by the digest the push left. A
`tofu apply` by hand needs the image built and Docker; against AWS also the `aws` CLI, for the ECR
login. A tag already in an AWS registry is never pushed over.

To build or push by hand:

```bash
mill rqlite.image --tag 1.0.6
mill rqlite.imagePush --registry <ecr-host> --repository captal-rqlite --tag 1.0.6
```

The registry needs its own `docker login` first. Nothing has ever pushed this image to AWS from the
build: every tag in a deployed environment was pushed by hand.

## The entrypoint's two environments

IP discovery is chosen by a flag, not by probing:

```sh
if [ "$RQLITE_LOCAL" = "true" ]; then
  PRIVATE_IP=$(hostname -i | cut -d' ' -f1)
else
  PRIVATE_IP=$(wget -qO- "$ECS_CONTAINER_METADATA_URI_V4" | ...)
fi
```

`modules/rqlite` sets `RQLITE_LOCAL` from `var.environment == "local"`. On Fargate the metadata
endpoint is the only correct source, so an empty answer there stays **fatal** — falling back would
start a node under the wrong Raft identity instead of failing. Keep that property if you touch this.

LocalStack does not serve that endpoint at all, which is why the flag exists.

## Checklist

1. Edit `docker/rqlite.Dockerfile` or `docker/rqlite-entrypoint.sh`.
2. If the engine version moved: update `rqliteVersion` and the `rqlite-jdbc` dependency in
   `build.mill` to match.
3. Bump `rqlite_image_tag` in every environment you intend to move.
4. `sh -n docker/rqlite-entrypoint.sh` — a syntax error here fails the container, not the build.
5. `mill rqlite.image` to confirm it builds.
6. `mill mcp.test` — the local apply pushes it and stands the cluster up.
7. Check the cluster formed, not just that the tasks started:
   `docker exec <task> wget -qO- http://localhost:5001/nodes` — expect one leader and the rest
   voters.

## Anti-patterns

- ❌ Changing the entrypoint without bumping `rqlite_image_tag`. The image changes, the task
  definition does not, nothing rolls.
- ❌ Picking a tag in the build instead of reading it from the tfvars.
- ❌ Moving the engine without the driver. The failure mode is silent: refused statements stop
  raising.
- ❌ `:latest` anywhere. That is how the engine moved two major versions with nobody deciding.
- ❌ Making the ECS metadata lookup fall back when it returns nothing on Fargate.
- ❌ Assuming the cluster is healthy because the tasks are `running`. Ask `/nodes` for a leader.
