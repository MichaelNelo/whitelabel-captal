---
name: run-locally
description: Use to spin up the captal stack on your dev machine — the local configuration in LocalStack, the MCP backoffice, a deployed portal, the guest API, and the client bundle. Triggers on "run locally", "local dev", "start dev", "correr en local", "levantar local".
version: 3.1.0
---

# Run the stack locally

## What you need running

1. **The local configuration** — the same OpenTofu configuration production uses, applied to
   LocalStack by `mcp.dev`: rqlite, the assets bucket and distribution, and the cluster a deploy
   attaches to. Needs a LocalStack licence (`LOCALSTACK_AUTH_TOKEN` in `.env`).
2. **MCP backoffice** (`mill mcp.dev`) — the only writer of content. Applies the configuration,
   migrates its rqlite, publishes the portal, the MCP App scripts and the API image, and serves the
   backoffice on `:8081`.
3. **The portal** — `deploy_location` stands a location up in LocalStack as production does, at
   `https://<id>.cloudfront.localhost.localstack.cloud:4567/<slug>/`. Or `mill api.dev --slug
   <slug>` for the faster loop on `:8080`. Either needs a location row to already exist.
4. **Client bundle** — built and served from disk in `api.dev`'s dev mode.

There is no provisioning directory any more. A fresh database has no locations, no surveys, no
videos — everything is created by calling MCP tools, not by pointing the server at a folder of
YAML.

## Step 1 — Start the MCP backoffice

```bash
mill mcp.dev            # localhost only
mill mcp.dev --tunnel   # also public, through Cloudflare quick tunnels
```

Six steps, in order:

1. Ensures a `localstack-dev` container on port 4567.
2. **Applies the `local` terraform environment** against it: builds and pushes the rqlite image,
   then the rqlite service (three nodes), the assets bucket with its CORS rule, the distribution,
   and the cluster, subnets, listener and roles. On a fresh container it is **minutes, not
   seconds** — it is not hung. Without a LocalStack licence it stops here and says so.
3. Waits for that rqlite to have a leader on `localhost:5001`, then runs `platform.Migrate`.
4. Runs the release `mill ci.run` makes, with LocalStack's variables: builds and pushes the API and
   backoffice images under their next versions (`mill show api.releaseVersion`; git-cliff, from the
   conventional commits since each one's last tag), moves every deployed location onto the
   new API image, and publishes the client — `bundle/` and `ui/` — and copies it under every
   deployed location's prefix. So a deploy from here runs the real API, and what is already
   deployed runs the build being run. To run that release on its own, waiting for the locations as
   CodeBuild does: `CAPTAL_ENV=local AWS_ENDPOINT_URL=http://localhost:4567 mill ci.run`.
5. With `--tunnel`, opens two Cloudflare quick tunnels, to `:8081` and to LocalStack on `:4567`,
   as the devenv processes `tunnel-mcp` and `tunnel-localstack`, and waits for their
   `https://<random>.trycloudflare.com` URLs. A tunnel already open is reused, so the URLs stay the
   same between runs until `mill mcp.stop`. No account or token is needed. The MCP App pages load their scripts and PUT uploads through
   LocalStack's tunnel (`SCRIPT_BASE_URL`, `PRESIGN_ENDPOINT_URL`), since a page rendered by a hosted
   client may not reach localhost. The tunnels close when the server exits. Without the flag the
   step is skipped.
6. Starts the backoffice on `:8081` with a **fixed** admin token, `dev-token`. With `--tunnel` the
   server is public, so the admin token is random and printed instead, and the server's address is
   the tunnel URL. `--token <value>` overrides either.

The backoffice's own `Main` then runs `McpMigrate` itself as part of its boot, after checking the API
schema is already at the version it requires — that check is what fails loudly if `platform.Migrate`
above didn't run.

Every token the task reads lives in `.env`, which `.envrc` sources and `.gitignore` ignores.
`.env.example` lists the names. Nothing is read from a tracked file.

The database is the configuration's three-node rqlite in LocalStack. The backoffice and `api.dev`
reach it on `localhost:5001`, the port one of its tasks publishes; a deployed location's task
reaches it as `rqlite.captal.local:5001`, by service discovery as in production, which `mcp.dev`
passes the server as `TASK_DB_URL`. It lives in LocalStack's tasks, so recreating the container
empties it.

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

## Step 3 — Serve the portal

As production does: call `deploy_location` and open
`https://<id>.cloudfront.localhost.localstack.cloud:4567/<slug>/` with the query string below. The
`<id>` is the distribution's, which `PUBLIC_BASE_URL` carries.

Or, for the faster loop, run the guest API on this machine:

```bash
mill api.dev --slug <that location's slug>
```

In order: waits for the configuration's rqlite (shared with step 1 — the backoffice writes what this
reads); runs the migrations; renders `client/index.html` from the template; builds the client
(`fastLinkJS` by default, or the Vite-processed `bundle` when `CLIENT_LINKER=full`); then starts the
server as the devenv process `api`, with `LOCATION_SLUG` set to `--slug` and `DB_URL` at that rqlite,
and returns once it listens on `:8080`. It runs beside the backoffice; `mill api.stop` stops it and
`devenv processes logs api` shows its log.

**The location must already exist** — `ServerSettings`/`AppLayers` resolve `LOCATION_SLUG` against
the `locations` table during layer construction, and fail the whole process with `LocationNotFound`
if it's absent. There is nothing in `api.dev` that creates it; that's step 2.

Equivalent by hand, if you want the pieces separately:

```bash
export DB_URL="jdbc:rqlite:http://localhost:5001"
export LOCATION_SLUG=cafe-centro
mill api.run
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
https://<id>.cloudfront.localhost.localstack.cloud:4567/<slug>/?id=AA:BB:CC:DD:EE:FF&ap=AA:BB:CC:DD:EE:01&ssid=Dev&url=http%3A%2F%2Fexample.com&click_id=local-dev-001
http://localhost:8080/<slug>/?id=AA:BB:CC:DD:EE:FF&ap=AA:BB:CC:DD:EE:01&ssid=Dev&url=http%3A%2F%2Fexample.com&click_id=local-dev-001
```

The first is a deployed location through the distribution; the second is `api.dev`.

Required params: `id` (→ `X-Client-Mac`), `click_id` (→ `X-Click-Id`). Missing either on a fresh
session → `ApiError.SessionMissing` → the SPA shows the error page.

## Step 5 — Tests

```bash
mill core.test   # pure domain tests
mill api.test     # end-to-end against its own rqlite-test container (port 4011)
mill mcp.test     # tool-level specs against rqlite-mcp-test (4012) and rqlite-mcp-e2e (4013);
                     # the deploy specs need a LocalStack licence and a terraform apply the build
                     # runs for you — see docs/testing.adoc
mill client.compile   # Scala.js compile check; there is no JS test runner
```

Each Docker-backed test task checks and restarts its own container on every invocation — an
operator removing a container between runs is caught, not silently misread as a schema problem.
The suites never touch the configuration's rqlite on 5001, which holds your local content.

## Hot-reload tips

- Scala source change under `api`/`platform`/`core` → re-run `mill api.dev` (or `mill -w
  api.dev` to have Mill watch and restart).
- Client source change → `mill -w client.bundle` (or `-w client.fastLinkJS` for the faster, less
  processed output) to keep rebuilding; refresh the browser to reload.
- Content change (survey wording, a new video, a translation) → nothing to restart. The backoffice
  writes straight to the database the API reads on every request.

## Common issues

| Symptom | Fix |
|---|---|
| `nothing ready at http://localhost:5001/readyz` | The configuration's rqlite isn't up. `mill mcp.dev` applies it; for `api.dev`, start `mcp.dev` first. If LocalStack is up and the service is not, `mill mcp.destroyLocal` and start again. |
| `mcp.dev runs against the local configuration … LocalStack runs most of that only with a licence` | No `LOCALSTACK_AUTH_TOKEN` in `.env`. There is no unlicensed mode. |
| `AlreadyExists` / `EntityAlreadyExists` from the terraform apply | LocalStack holds resources the state doesn't know about; the two came apart. `mill mcp.test.resetLocalStack` stops LocalStack and removes its state, and the next run builds both together. |
| A task fails with `the process exited` | The process it started died; the error carries the end of its log, and `devenv processes logs <name>` has the rest. |
| `<name> did not start` | devenv's process manager runs older definitions than `devenv.nix`: stop each process, then `devenv processes down`, and run again. |
| The backoffice's upload, UniFi or run page is blank | `mcp.dev` publishes the page scripts at step 4, so check that they answer: `curl -I -H "Origin: https://x" http://localhost:4567/captal-local-assets/ui/upload.js` should give 200 with `Access-Control-Allow-Origin: *`. If they do, the browser did not load them. Without `--tunnel` they are served from `localhost:4567`, and a page rendered by a hosted client such as claude.ai may not be allowed to reach localhost; the browser console says so. Run with `--tunnel`. |
| A deployed location's page loads but its API calls fail | Its task may not be running yet, or failing to start: `get_deploy_status` names the step, and `/ecs/captal-<slug>` in LocalStack's logs says why. |
| Anything local is wedged and you want a clean slate | `mill mcp.destroyLocal` — every devenv process this build runs, every container it starts, the ECS task containers LocalStack spawned, the images built here, and the local tofu state under `.devenv/state/localstack/tofu/`. The next run builds the container and its state together. |
| `LocationNotFound` and the process exits immediately | The slug doesn't exist in `locations` yet. Create it through the backoffice first (step 2). |
| `No result set returned` after a restart | rqlite's data was wiped — it lives in LocalStack's tasks, and a recreated LocalStack starts it empty. Re-create the content through the backoffice. |
| Reset button doesn't appear on the Ready page | `ENVIRONMENT=dev` wasn't set when building the client, or Mill cached the old `BuildInfo`. `mill clean client.generatedSources && ENVIRONMENT=dev mill client.bundle`. |
| `[welcome.title]` literal shown in the SPA | The location's `localized_texts` don't cover that key yet. Check with the backoffice's `get_i18n_status`, write it with `set_translations` — see skill `add-i18n-key`. |
| `SessionMissing` on every request | Missing `id=`/`click_id=` query params on the first visit (or `X-Client-Mac`/`X-Click-Id` headers in a curl test). |
| Click "Start" → infinite spinner | The API likely returned 500. Tail the terminal `mill api.dev` is running in. |

## Anti-patterns

- ❌ Looking for a `shared/` or `locations/<slug>/` directory, or a `captal` CLI command, to seed
  content — none of that exists. Everything is MCP tool calls against the backoffice.
- ❌ Running `api.dev` against a slug with no location row and expecting it to create one — it
  fails fast with `LocationNotFound` instead.
- ❌ Pointing a test run at the configuration's rqlite on 5001 — the test suites clear and reseed
  their own schema and will wipe your local content if pointed at it.
- ❌ Running `mill client.bundle` without `ENVIRONMENT=dev` when testing a dev-only feature — you'll
  be debugging a production-build bug that doesn't exist in dev.
- ❌ Editing `out/client/bundle.dest/*` or `out/client/fastLinkJS.dest/*` directly — Mill regenerates
  them on the next build and your edits vanish.
