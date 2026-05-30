---
name: bump-cli-version
description: Use when releasing a new version of the captal CLI to the public S3 bucket. Covers version bump, publish, operator update flow, and (since 2.1.0) the schema-migration registry. Triggers on "bump cli", "release cli", "publish cli", "subir cli", "publicar captal".
version: 1.1.0
---

# Release a new captal CLI version

## When to use

After any change in `cli/`, `provision/` (CLI depends on it), or skills under `cli/resources/templates/skills/`. Each release is published to a public S3 bucket; operators run `captal update` to pull it.

## Pre-flight

- `./mill cli.compile` clean.
- If the CLI uses new features from the API base, those base images should already be in ECR (operator may upgrade `images.api` before pushing). Loose coupling — not required.
- Logged in to AWS (the publish task uses `aws s3 cp`).

## Pick a version

Last published:

```bash
curl -s https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/version.txt
```

Bump SemVer:
- **Patch** (`1.6.2 → 1.6.3`): skill copy edit, bug fix, no flag changes.
- **Minor** (`1.6.x → 1.7.0`): new subcommand, new flag, new skill.
- **Major** (`1.x → 2.0.0`): removed/renamed subcommand or flag, behavior change that breaks existing operator workflows.

## Steps

### 1. Update the version constant

`cli/src/whitelabel/captal/cli/Main.scala`:

```scala
private val cliVersion = "X.Y.Z"
```

`cliVersion` is read by `captal --version`, by `UpdateCommand` for comparison, embedded in the bash/bat wrappers' `version.txt`, AND used by the schema-migration warning hook (see step 1b).

### 1b. If this release breaks the provision schema — register a migration

When the release renames, removes, or changes the semantics of a YAML field that operators write (e.g. `location.yaml`, `shared/captal.yaml`), append a `Migration` entry to `cli/src/whitelabel/captal/cli/migrations/Migration.scala::Migrations.all`:

```scala
Migration(
  version = SemVer(X, Y, Z),
  description = "<one-line summary>",
  fileGlob = "locations/*/location.yaml",  // or other pattern
  ops = List(
    YamlOp.Rename(YamlPath("unifi.site"), YamlPath("unifi.siteId")),
    YamlOp.Delete(YamlPath("unifi.unifiOs"))
  )
)
```

Rules:
- **APPEND only** — never modify or remove earlier entries. They're idempotent and harmless on already-migrated files.
- The `version` must match the release tag (`X.Y.Z` in step 1) so the warning hook fires for operators upgrading from a prior version.
- Paths use the `YamlPath` opaque type with **compile-time validation** via `compiletime.ops.string.Matches`. Malformed literals like `YamlPath("a..b")` fail compilation.
- `Add` ops are silent in the warning hook (operators see them only when running `captal migrate`). `Delete` and `Rename` fire warnings.
- After adding the migration, run `./mill cli.test` — the test suite in `cli/test/.../migrations/MigrationsSpec.scala` should still pass (or extend it if the new ops cover novel patterns).

The operator's experience after upgrading:
- Their first invocation of any `captal` command after `captal update` shows a warning listing pending migrations.
- They run `captal migrate --dry-run` to preview, then `captal migrate` (or `--yes` for CI) to apply.
- `.captal/state.json` is updated automatically — the warning won't repeat.

### 2. Publish

```bash
./mill cli.publishS3 --bucket captal-cli-releases-dev --version X.Y.Z
```

This:
- Builds the assembly (Mill auto-runs `releaseAssets` task dependency).
- Uploads `captal.jar`, `captal`, `captal.bat`, `version.txt` to BOTH:
  - `s3://captal-cli-releases-dev/vX.Y.Z/` (immutable)
  - `s3://captal-cli-releases-dev/latest/` (overwrites)
- Bucket is public-read on `latest/*` and `v*/*` — operators fetch via plain HTTPS.

### 3. Verify

```bash
curl -s https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/version.txt
# → X.Y.Z
```

### 4. Operator update path

Operators with an existing install run:

```bash
captal update         # downloads new jar to captal.jar.new
captal --version      # wrapper swaps .new → captal.jar before launching → reports X.Y.Z
captal skills update  # overwrites .agents/skills/ with the new bundled skills (always overwrites)
```

The wrappers (`captal` bash, `captal.bat`) handle the swap automatically — required for Windows where the JVM holds the JAR locked while running.

## Update AGENTS.md

Add a line in the "Versiones publicadas en ECR / S3" tabla:

```
- **CLI**: ... → `1.6.2` → `X.Y.Z` (<one-line summary of what changed>)
```

If the new version added a subcommand, also update the relevant skill mentioning that subcommand (e.g. `add-location`, `deploy-location`).

## Bootstrap for a brand-new operator

Don't have any `captal` installed yet:

**Linux / macOS**:
```bash
mkdir -p ~/.local/bin && cd ~/.local/bin
curl -O https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/captal.jar
curl -O https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/captal
chmod +x captal
# ensure ~/.local/bin is in PATH
captal init --claude
```

**Windows**:
```powershell
$dir = "$env:USERPROFILE\.captal"
mkdir $dir -Force | Out-Null
curl.exe -o "$dir\captal.jar" https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/captal.jar
curl.exe -o "$dir\captal.bat" https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/captal.bat
# add $dir to PATH (System Properties → Environment Variables)
captal init --claude
```

## Anti-patterns

- ❌ Forgetting to bump `cliVersion` — `captal update` won't detect a new release because `version.txt` matches.
- ❌ Reusing a version tag — S3 versions the bucket so the previous content is recoverable, but `vX.Y.Z/` should be immutable in practice.
- ❌ Publishing a CLI that depends on an unreleased API feature — operators may upgrade CLI without upgrading API and get cryptic errors. If unavoidable, gate behind a flag.

## Caveats

- **Wrapper updates**: when you change the bash/batch wrapper logic (rare), operators on old wrappers won't pick up the new ones automatically. They'd have to re-download the wrapper manually. Mention in release notes.
- **Skills overwrite**: `captal skills update` overwrites by default (since 1.5.4+). If an operator edited a bundled skill locally, their edits are lost. Recommend keeping operator-specific skills in separate filenames.
- **slf4j NOP**: the CLI bundles `slf4j-nop` to silence AWS SDK warnings. If you swap to a different logging binding, expect log output to change shape.
