---
name: run-locally
description: Use to spin up the captal stack on your dev machine — rqlite container, API server, client bundle, and a test location. Triggers on "run locally", "local dev", "start dev", "correr en local", "levantar local".
version: 1.0.0
---

# Run the stack locally

## What you need running

1. **rqlite container** (the DB)
2. **API server** (`./mill api.dev` — runs `infra.Migrate` then HTTP server on 8080)
3. **Client bundle** built and served (in dev mode the API serves it from the filesystem)
4. **Provision data** under `<dir>/shared/` and `<dir>/locations/<slug>/`

## Step 1 — Start rqlite

```bash
docker run --rm -d \
  --name captal-rqlite-dev \
  -p 4001:4001 -p 4002:4002 \
  rqlite/rqlite:latest \
  -node-id captal-dev -http-addr 0.0.0.0:4001 -raft-addr 0.0.0.0:4002 \
  -bootstrap-expect 1
```

Verify: `curl http://localhost:4001/status`. Stop with `docker stop captal-rqlite-dev`.

Data persists in the container's filesystem; restart loses it (matches Fargate ephemeral behavior). If you want persistence: `-v $PWD/.rqlite-data:/rqlite/file` in the docker run.

## Step 2 — Build the client bundle

```bash
./mill clean client.generatedSources   # invalidate BuildInfo cache when toggling ENVIRONMENT
ENVIRONMENT=dev ./mill client.bundle
```

`ENVIRONMENT=dev` flips `BuildInfo.isDevMode = true` which enables the `/api/dev/reset-phase` button on the Ready page. **Always `clean` before flipping** — Mill caches generated sources and won't re-run otherwise.

Output: `out/client/bundle.dest/{index.html,main.js[.gz],styles.css[.gz],brand-icon.svg}`.

## Step 3 — Run the API

```bash
export DB_URL="jdbc:rqlite:http://localhost:4001"
export LOCATION_SLUG=cafe-centro
export PROVISION_DIR=$PWD/locations/cafe-centro
export SHARED_DIR=$PWD/shared
export SERVER_DEV_MODE=true
export SERVER_DEV_ENDPOINTS=true
./mill api.dev
```

Or via the helper task (defaults built in):

```bash
./mill api.dev --slug cafe-centro --dir locations/cafe-centro --shared-dir shared
```

On startup the server:
1. Runs Flyway against rqlite (`/db/migration/V*.sql`).
2. Logs `Server starting on 0.0.0.0:8080`.
3. Logs `Session cookie: captal_session_cafe-centro (path=/cafe-centro)` from the new `SessionCookieConfig`.
4. Provisions the location from `PROVISION_DIR` on first request.

If `SERVER_DEV_MODE=true`, the API also serves static client assets from disk at `/`, `/main.js`, etc. — useful for the dev loop.

## Step 4 — Provision shared resources

Once-only (or after any change to `shared/`):

```bash
java -jar out/cli/releaseAssets.dest/captal.jar shared push
```

This launches an ephemeral container locally? No — `shared push` provisions via the CLI directly hitting rqlite via the API's `/api/admin/provision-shared` flow… **actually** in local-dev `SHARED_DIR` is read at API startup by `ProvisionService.runShared`. So:

- **Dev mode**: API at startup reads `SHARED_DIR` and provisions surveys/advertisers — no separate `shared push` needed.
- **Prod mode**: ephemeral ECS task runs `infra.assembly` which calls `Migrate` + `ProvisionService.runShared`.

For local dev, just make sure `SHARED_DIR` is set when starting the API.

## Step 5 — Browse

`http://localhost:8080/` — assumes you're testing a single-location dev setup with `LOCATION_SLUG=cafe-centro`. The basePath detection in `Router.scala:27` picks up the slug if you visit `http://localhost:8080/cafe-centro/`. For dev you can omit the slug.

UniFi-style URL to test the captive-portal flow:

```
http://localhost:8080/cafe-centro/?id=AA:BB:CC:DD:EE:FF&ap=AA:BB:CC:DD:EE:01&ssid=Dev&url=http%3A%2F%2Fexample.com&click_id=local-dev-001
```

Required params: `id` (X-Client-Mac), `click_id` (X-Click-Id). Missing either → `ApiError.SessionMissing` → SPA shows the error page.

## Step 6 — Tests

```bash
./mill api.test        # 47 suites, ~30s. Uses TapirStubInterpreter against test rqlite container.
./mill core.test       # core domain tests
./mill client.compile  # Scala.js compile check (no test runner for JS)
```

Tests pull their own rqlite via `TestFixtures.migrate`. Make sure your dev rqlite is on a separate port if conflict: tests use port `4011` per `api/test/resources/test.conf` `database.jdbcUrl`.

## Hot-reload tips

- Scala source change → `./mill api.dev` re-runs incremental compile + restart (Mill watches by default if you use `./mill -w api.dev`).
- Client source change → `./mill -w client.bundle` to keep rebuilding. Refresh browser to reload.
- YAML provision change → restart the API (re-reads `PROVISION_DIR` on startup).

## Common issues

| Symptom                                                  | Fix                                                                                              |
|----------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `Connection refused` on `localhost:4001`                 | rqlite container not running. `docker ps                                                         |
| `No result set returned` after restart                   | rqlite data wiped (ephemeral). Re-restart API to re-provision, or use `-v` volume mount.         |
| Reset button doesn't appear on Ready page                | `ENVIRONMENT=dev` wasn't set OR Mill cached the BuildInfo. `./mill clean client.generatedSources && ENVIRONMENT=dev ./mill client.bundle`. |
| `[welcome.title]` literal shown in SPA                   | i18n YAMLs missing or location not provisioned. Check `LOCATION_SLUG` + `PROVISION_DIR` env.     |
| `SessionMissing` on every request                        | Missing `id=` / `click_id=` query params (or X-Client-Mac/X-Click-Id headers in curl tests).     |
| Click "Start" → infinite spinner                         | API likely returned 500. Tail `~/.cache/captal-dev.log` or terminal where `./mill api.dev` runs. |

## Anti-patterns

- ❌ Sharing the local rqlite between dev and tests — tests `cleanOnValidationError` will wipe your data.
- ❌ Editing `out/client/bundle.dest/*` directly — Mill regenerates on next bundle, your edits vanish.
- ❌ Running `./mill client.bundle` without `ENVIRONMENT=dev` when testing dev features — you'll be debugging a production-build bug that doesn't exist in dev.
