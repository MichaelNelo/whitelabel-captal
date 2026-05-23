---
name: bump-cli-version
description: Use when releasing a new version of the captal CLI to the public S3 bucket. Covers version bump, publish, and operator update flow. Triggers on "bump cli", "release cli", "publish cli", "subir cli", "publicar captal".
version: 1.0.0
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

`cliVersion` is read by `captal --version`, by `UpdateCommand` for comparison, and embedded in the bash/bat wrappers' `version.txt`.

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
