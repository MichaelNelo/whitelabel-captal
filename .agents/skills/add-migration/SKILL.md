---
name: add-migration
description: Use when adding a new Flyway migration to evolve the rqlite schema. Triggers on "add migration", "new migration", "alter table", "schema change", "migración db", "nueva columna".
version: 1.1.0
---

# Add a Flyway migration

## When to use

Any persisted change to the **portal** schema (new column, new table, index, soft-delete flag) —
the tree under `platform/resources/db/migration`, owned by the API. (The backoffice has its own,
separate migration tree under `mcp/resources/db/migration-mcp`, for `mcp_tokens` alone; this skill
is about the portal's.) Migrations run via `platform.Migrate` as part of the `captal-api` image's
own startup `CMD`, on every location task that boots on it — there is no separate provisioning task
any more.

## Steps

### 1. Pick the next version number

```bash
ls platform/resources/db/migration/ | tail -3
```

Pick `Vn+1` where `n` is the last `Vn__*.sql`. Versions are linear; gaps fail Flyway.

### 2. Create the file

`platform/resources/db/migration/V<n>__<descriptive_name>.sql`. Naming: snake_case, present-tense verb (`add_`, `rename_`, `drop_`). Example: `V16__add_locale_to_users.sql`.

### 3. Write the SQL

```sql
-- Brief explanation of WHY, not WHAT.
-- E.g. "Per-user preferred locale persisted so the SPA can pre-select the dropdown
-- on next visit without depending on browser Accept-Language."
ALTER TABLE users ADD COLUMN locale TEXT NOT NULL DEFAULT 'es';
CREATE INDEX idx_users_locale ON users(locale);
```

#### Rqlite/SQLite caveats

- **`ALTER TABLE`** only supports a subset: `ADD COLUMN`, `RENAME COLUMN`, `RENAME TO`, `DROP COLUMN` (SQLite 3.35+). For type changes / multi-column reshuffles, do the **create-temp + copy + drop + rename** dance.
- **`NOT NULL` requires a `DEFAULT`** for `ADD COLUMN` (legacy rows need a value).
- **Foreign keys** are declared but rqlite doesn't enforce them by default — don't rely on FK cascades.
- **No transactional DDL** in SQLite — if a migration fails mid-way the partial change persists. Keep migrations atomic in intent.

### 4. Add the corresponding row field

If you added a column, update the `*Row` case class in `platform/src/whitelabel/captal/platform/rows.scala`. Keep the field order matching column order in `CREATE TABLE` to make Quill happy. Use `Option[T]` for nullable columns, `T` for `NOT NULL DEFAULT ...`.

### 5. Update writers / queries

There is no `EntityWriter` or `ProvisionService` — content is written directly by the MCP
backoffice's tools, each with its own Quill `insert`/`update` statement:

- **The MCP tool that owns this table**: if an operator needs to write the new column, add it to
  the relevant `insertValue`/`update` call in `mcp/src/whitelabel/captal/mcp/tools/<Group>Tools.scala`
  (e.g. `VideoTools` for `advertiser_videos`, `LocationTools` for `locations`), guarded by
  `Writes.expectOne`/`expectAtLeastOne` like every other write in that module — see
  `docs/database.adoc`'s write-verification rule.
- **Repositories**: if the column is read on the guest-facing hot path, surface it in the relevant
  `platform/repositories/*RepositoryQuill.scala` query.

### 6. Test

Add a row in `api/test/src/whitelabel/captal/api/TestFixtures.scala` that exercises the new column (insert + read back). Run `./mill api.test` — `TestFixtures.migrate` runs Flyway on the test rqlite container so a broken migration fails the suite immediately.

### 7. Deploy

The migration is baked into the `captal-api` image's own classpath — there's no separate
provisioning image to rebuild. Follow skill `bump-api-version`: build and push `captal-api` with a
new tag, point the environment's `image_tag` at it, then call `deploy_location` for each location.
Every location task that starts on the new image runs `Migrate` as part of its own boot; it's
idempotent, so a location still on the old image and one already moved over both end up correct.

If the backoffice now depends on this column (a tool reads or writes it immediately), bump
`RequiredApiVersion` in `mcp/src/whitelabel/captal/mcp/McpMigrate.scala` to this migration's number.
`McpMigrate` checks the API's highest applied version at its own startup and refuses to serve
otherwise — that's what turns "the backoffice was deployed ahead of the API" into a loud boot
failure instead of a tool call failing on a column that isn't there yet.

## Verification

After the first location moves to the new image:
- `aws logs tail /ecs/captal-<slug> --since 5m` — look for a Flyway "Successfully applied N
  migration(s)" line naming your new version; a location that boots later on the same image logs 0.
- Query the schema directly: `curl -G "$RQLITE_URL/db/query" --data-urlencode "q=PRAGMA table_info(<table>)"`.

## Anti-patterns

- ❌ Editing an already-applied `V<n>__*.sql` file — Flyway tracks checksums and refuses to run. Add a new `V<n+1>__fix_*.sql` instead.
- ❌ Renumbering existing migrations.
- ❌ Removing columns without a deprecation period — running APIs (still on the old image) may write NULL or fail to insert.
