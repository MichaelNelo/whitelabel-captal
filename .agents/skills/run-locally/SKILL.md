---
name: run-locally
description: Use to spin up the captal stack on your dev machine — rqlite, the MCP backoffice, the guest API, and the client bundle. Triggers on "run locally", "local dev", "start dev", "correr en local", "levantar local".
version: 2.0.0
---

# Run the stack locally

## What you need running

1. **rqlite** — one Docker container, started automatically.
2. **MCP backoffice** (`./mill mcp.dev`) — the only writer of content. Starts rqlite, runs both
   schema's migrations, ensures LocalStack, and serves the backoffice on `:8081`.
3. **Guest API** (`./mill api.dev --slug <slug>`) — reads what the backoffice wrote, serves the
   portal on `:8080`. Needs a location row to already exist.
4. **Client bundle** — built and served from disk in dev mode.

There is no provisioning directory any more. A fresh database has no locations, no surveys, no
videos — everything is created by calling MCP tools, not by pointing the server at a folder of
YAML.

## Step 1 — Start the MCP backoffice

```bash
./mill mcp.dev
```

In order, this task: ensures the `rqlite-dev` container on port 4001 (image pinned to
`rqlite/rqlite:10.6.1` — the build recreates the container if an old one is on the wrong image);
runs `platform.Migrate` against it; ensures a `localstack-dev` container and the
`captal-assets-dev` bucket; applies the `local` terraform environment against LocalStack when a
`LOCALSTACK_TOKEN`/`LOCALSTACK_AUTH_TOKEN` is configured (so the deploy tools exist; without one
they're simply not registered); and starts the backoffice on `:8081` with a **fixed** admin token,
`dev-token`. The backoffice's own `Main` then runs `McpMigrate` itself as part of its boot, after
checking the API schema is already at the version it requires — that check is what fails loudly if
`platform.Migrate` above didn't run.

It prints a ready-to-paste MCP client config:

```json
"captal": {
  "command": "npx",
  "args": ["mcp-remote", "http://localhost:8081/mcp",
           "--header", "Authorization: Bearer dev-token"]
}
```

If the `locations` table is empty, it also prints a reminder that nothing is seeded yet.

## Step 2 — Create content through the backoffice

Point an MCP client at the config above, and drive it through a model (or by hand). At minimum, to
get a portal that boots: `create_location`, plus enough of `create_identification_survey` /
`create_advertiser` / `create_advertiser_video` / `create_promo_video` and `set_translations` that
the guest journey has something to serve. See `docs/mcp.adoc` for the tool catalogue, or the
`onboard-location` MCP prompt, which reads the database and tells you exactly what's missing for a
given slug.

## Step 3 — Run the guest API

```bash
./mill api.dev --slug <that location's slug>
```

In order: ensures `rqlite-dev` (shared with step 1 — the backoffice writes what this reads, so
running them against two different databases defeats the point); renders `client/index.html` from
the template; builds the client (`fastLinkJS` by default, or the Vite-processed `bundle` when
`CLIENT_LINKER=full`); then forks the server with `LOCATION_SLUG` set to `--slug`.

**The location must already exist** — `ServerSettings`/`AppLayers` resolve `LOCATION_SLUG` against
the `locations` table during layer construction, and fail the whole process with `LocationNotFound`
if it's absent. There is nothing in `api.dev` that creates it; that's step 2.

Equivalent by hand, if you want the pieces separately:

```bash
export DB_URL="jdbc:rqlite:http://localhost:4001"
export LOCATION_SLUG=cafe-centro
./mill api.dev
```

`PROVISION_DIR` and `SHARED_DIR` still exist as settings but are read and ignored — don't set them
expecting the server to provision from a directory. Content is read-only here, always.

**UniFi**: if the location's row has no `unifi_host`/`unifi_api_token`/`unifi_site_id` (set through
the backoffice's `configure_unifi` tool), `UnifiAccessHandler` logs "no config" and skips — the
session stays at `Phase.Ready` after `/api/finish`. To test the full path, configure a real
controller's credentials and either run on its LAN or point `UNIFI_PROXY_URL` at a reachable proxy.

On startup the server logs the bind address, the dev flags, the session cookie name and path
(`captal_session_<slug>` at `Path=/<slug>`), and the rendered pattern of every mounted route.

If `SERVER_DEV_MODE=true` (the `application.conf` default locally), the API also serves the client
bundle, `assets/styles.css` and `brand-icon.svg` from disk — that's the dev loop.

## Step 4 — Browse

```
http://localhost:8080/<slug>/?id=AA:BB:CC:DD:EE:FF&ap=AA:BB:CC:DD:EE:01&ssid=Dev&url=http%3A%2F%2Fexample.com&click_id=local-dev-001
```

Required params: `id` (→ `X-Client-Mac`), `click_id` (→ `X-Click-Id`). Missing either on a fresh
session → `ApiError.SessionMissing` → the SPA shows the error page.

## Step 5 — Tests

```bash
./mill core.test   # pure domain tests
./mill api.test     # end-to-end against its own rqlite-test container (port 4011)
./mill mcp.test     # tool-level specs against rqlite-mcp-test (4012) and rqlite-mcp-e2e (4013);
                     # the deploy specs need a LocalStack licence and a terraform apply the build
                     # runs for you — see docs/testing.adoc
./mill client.compile   # Scala.js compile check; there is no JS test runner
```

Each Docker-backed test task checks and restarts its own container on every invocation — an
operator removing a container between runs is caught, not silently misread as a schema problem.
Make sure your dev rqlite stays on its own port if you're also running the test containers.

## Hot-reload tips

- Scala source change under `api`/`platform`/`core` → re-run `./mill api.dev` (or `./mill -w
  api.dev` to have Mill watch and restart).
- Client source change → `./mill -w client.bundle` (or `-w client.fastLinkJS` for the faster, less
  processed output) to keep rebuilding; refresh the browser to reload.
- Content change (survey wording, a new video, a translation) → nothing to restart. The backoffice
  writes straight to the database the API reads on every request.

## Common issues

| Symptom | Fix |
|---|---|
| `Connection refused` on `localhost:4001` | The `rqlite-dev` container isn't running. `docker ps` to check; `./mill api.rqliteSetup` or `./mill mcp.dev` ensures it. |
| `LocationNotFound` and the process exits immediately | The slug doesn't exist in `locations` yet. Create it through the backoffice first (step 2). |
| `No result set returned` after a restart | rqlite's data was wiped — the dev container has no volume by default. Re-create the content through the backoffice, or mount a volume yourself. |
| Reset button doesn't appear on the Ready page | `ENVIRONMENT=dev` wasn't set when building the client, or Mill cached the old `BuildInfo`. `./mill clean client.generatedSources && ENVIRONMENT=dev ./mill client.bundle`. |
| `[welcome.title]` literal shown in the SPA | The location's `localized_texts` don't cover that key yet. Check with the backoffice's `get_i18n_status`, write it with `set_translations` — see skill `add-i18n-key`. |
| `SessionMissing` on every request | Missing `id=`/`click_id=` query params on the first visit (or `X-Client-Mac`/`X-Click-Id` headers in a curl test). |
| Click "Start" → infinite spinner | The API likely returned 500. Tail the terminal `./mill api.dev` is running in. |

## Anti-patterns

- ❌ Looking for a `shared/` or `locations/<slug>/` directory, or a `captal` CLI command, to seed
  content — none of that exists. Everything is MCP tool calls against the backoffice.
- ❌ Running `api.dev` against a slug with no location row and expecting it to create one — it
  fails fast with `LocationNotFound` instead.
- ❌ Sharing the local `rqlite-dev` container with a test run — the test suites clear and reseed
  their own schema and will wipe your dev data if pointed at the same container.
- ❌ Running `./mill client.bundle` without `ENVIRONMENT=dev` when testing a dev-only feature — you'll
  be debugging a production-build bug that doesn't exist in dev.
- ❌ Editing `out/client/bundle.dest/*` or `out/client/fastLinkJS.dest/*` directly — Mill regenerates
  them on the next build and your edits vanish.
