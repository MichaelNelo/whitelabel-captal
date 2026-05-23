---
name: add-migration
description: Use when adding a new Flyway migration to evolve the rqlite schema. Triggers on "add migration", "new migration", "alter table", "schema change", "migración db", "nueva columna".
version: 1.0.0
---

# Add a Flyway migration

## When to use

Any persisted change to the schema (new column, new table, index, soft-delete flag). Migrations run at every API container startup via `infra.Migrate` and on the ephemeral `captal shared push` task.

## Steps

### 1. Pick the next version number

```bash
ls infra/resources/db/migration/ | tail -3
```

Pick `Vn+1` where `n` is the last `Vn__*.sql`. Versions are linear; gaps fail Flyway.

### 2. Create the file

`infra/resources/db/migration/V<n>__<descriptive_name>.sql`. Naming: snake_case, present-tense verb (`add_`, `rename_`, `drop_`). Example: `V16__add_locale_to_users.sql`.

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

If you added a column, update the `*Row` case class in `infra/src/whitelabel/captal/infra/rows.scala`. Keep the field order matching column order in `CREATE TABLE` to make Quill happy. Use `Option[T]` for nullable columns, `T` for `NOT NULL DEFAULT ...`.

### 5. Update writers / queries

- **EntityWriter**: include the column in `insertValue` and `onConflictUpdate` clauses.
- **ProvisionService**: pass the value from the YAML (or `None`) when calling the writer.
- **Repositories**: if the column is read on the hot path, surface it in the relevant `*Repository` queries.

### 6. Test

Add a row in `api/test/src/whitelabel/captal/api/TestFixtures.scala` that exercises the new column (insert + read back). Run `./mill api.test` — `TestFixtures.migrate` runs Flyway on the test rqlite container so a broken migration fails the suite immediately.

### 7. Deploy

Rebuild both base images (the migrations are baked into the assembly classpath):

```bash
./mill api.dockerBuild --repoUri <ecr>/captal-api-dev --tag vX.Y.Z
./mill api.dockerPush  --repoUri <ecr>/captal-api-dev --tag vX.Y.Z
./mill infra.dockerBuild --repoUri <ecr>/captal-provision-dev --tag vX.Y.Z
./mill infra.dockerPush  --repoUri <ecr>/captal-provision-dev --tag vX.Y.Z
```

Then operator bumps `shared/captal.yaml` images.api/provision → `captal shared push` (runs migration once) → `captal locations push-all` (each API task restart re-runs Flyway harmlessly — it's idempotent).

## Verification

After deploy:
- `aws logs tail /ecs/captal-shared --since 5m` — look for `Migrations complete: N executed` (N includes your new one on first run, then 0).
- Query the schema directly: `curl -G "$RQLITE_URL/db/query" --data-urlencode "q=PRAGMA table_info(<table>)"`.

## Anti-patterns

- ❌ Editing an already-applied `V<n>__*.sql` file — Flyway tracks checksums and refuses to run. Add a new `V<n+1>__fix_*.sql` instead.
- ❌ Renumbering existing migrations.
- ❌ Removing columns without a deprecation period — running APIs (still on the old image) may write NULL or fail to insert.
