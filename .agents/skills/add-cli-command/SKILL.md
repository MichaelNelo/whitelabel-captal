---
name: add-cli-command
description: Use when adding a new top-level command or subcommand to the captal CLI. Covers zio-cli pattern, AWS layer wiring, output conventions, and error handling. Triggers on "add cli command", "new cli subcommand", "captal command", "agregar comando cli".
version: 1.0.0
---

# Add a CLI command

## Mental model

The captal CLI uses **zio-cli**:

- An `enum CaptalCommand` enumerates ALL leaf commands the CLI supports (one case per leaf).
- Each leaf has a `private val <name>: Command[CaptalCommand]` that wires `Options` + `Args` + `.map` to construct a case.
- Groups (`shared`, `locations`, `video`, `skills`) are `Command(name, ..., Args.none).subcommands(<leaves>)`.
- `cliApp.run` pattern-matches the parsed `CaptalCommand` and dispatches to the implementation, providing AWS layers as needed.

Files:
- `cli/src/whitelabel/captal/cli/Main.scala` — enum, command wiring, dispatch.
- `cli/src/whitelabel/captal/cli/commands/<Name>Command.scala` — the actual implementation.
- `cli/src/whitelabel/captal/cli/AwsLayers.scala` — ZLayer factories for AWS SDK clients.
- `cli/src/whitelabel/captal/cli/{CaptalConfig,CliError,Output}.scala` — shared utilities.

## Steps

### 1. Decide the shape

- **New top-level command** (`captal foo`) vs **new subcommand** (`captal locations foo`).
- **Required AWS access**: which clients (`S3Client`, `EcsClient`, `ElasticLoadBalancingV2Client`, `EcrClient`, `CloudFrontClient`, `CloudWatchLogsClient`)? Each implies a layer from `AwsLayers`.
- **Needs `CaptalConfig`** (reads `shared/captal.yaml`)? Most commands do; meta commands like `captal update` don't.
- **Has args/options**? `captal locations push <slug>` has a single text arg; `captal update --url <u>` has an option with default.

### 2. Create the implementation

`cli/src/whitelabel/captal/cli/commands/FooCommand.scala`:

```scala
package whitelabel.captal.cli.commands

import software.amazon.awssdk.services.s3.S3Client
import whitelabel.captal.cli.{CaptalConfig, CliError, Output}
import zio.*

object FooCommand:

  type Env = CaptalConfig & S3Client    // narrow to what you actually need

  def run(slug: String): ZIO[Env, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      _      <- Output.header(s"Foo on '$slug'")
      _      <- doFoo(config, slug)
      _      <- Output.success(s"Done")
    yield ()

  private def doFoo(config: CaptalConfig, slug: String): ZIO[S3Client, CliError, Unit] =
    aws("S3 listObjects"):
      ZIO.serviceWithZIO[S3Client]: s3 =>
        ZIO.attemptBlocking:
          // ... your logic
          ()

  private def aws[R, A](operation: String)(effect: ZIO[R, Throwable, A]): ZIO[R, CliError, A] =
    effect.mapError(CliError.AwsError(operation, _))
end FooCommand
```

Conventions:
- Define a `type Env` that lists every service the command needs (lets `.provide(...)` errors point you at missing layers immediately).
- Wrap AWS calls in `aws("operation name")(...)` so failures map to `CliError.AwsError(op, throwable)` with a readable label.
- Use `Output.header` / `info` / `success` / `detail` / `warn` / `error` — consistent emoji + color across commands. Don't `println` directly.

### 3. Register in `CaptalCommand` enum

`cli/src/whitelabel/captal/cli/Main.scala`:

```scala
enum CaptalCommand:
  ...
  case Foo(slug: String)     // NEW
```

### 4. Build the `Command[CaptalCommand]` value

In `Main.scala`, somewhere near related commands:

```scala
private val foo: Command[CaptalCommand] = Command(
  "foo",                                  // command name
  Options.text("name").alias("n").optional,   // options (None if Args.none)
  slugArg)                                // args
  .withHelp(HelpDoc.p("Do foo with a slug. Optionally pass --name."))
  .map(slug => CaptalCommand.Foo(slug))   // build the enum case
```

For a leaf with only args (no opts):
```scala
private val foo: Command[CaptalCommand] = Command("foo", Options.none, slugArg)
  .withHelp(HelpDoc.p("Do foo"))
  .map(CaptalCommand.Foo(_))
```

For a leaf with only opts (no args):
```scala
private val foo: Command[CaptalCommand] = Command(
  "foo",
  Options.text("url").alias("u").withDefault("https://default"),
  Args.none)
  .withHelp(HelpDoc.p("Do foo. Defaults to default URL."))
  .map(url => CaptalCommand.Foo(url))
```

### 5. Attach to a parent (subcommand) or to root

**As a subcommand under `locations`**:
```scala
private val locations: Command[CaptalCommand] = Command("locations", Options.none, Args.none)
  .withHelp(HelpDoc.p("Manage locations"))
  .subcommands(locationsAdd, locationsPush, locationsPushAll, foo)   // NEW added
```

**As a top-level command**:
```scala
private val captal: Command[CaptalCommand] = Command("captal", Options.none, Args.none)
  .subcommands(init, shared, locations, video, skills, update, foo)
```

### 6. Dispatch in `cliApp`

In the big `cmd match` block:

```scala
case CaptalCommand.Foo(slug) =>
  FooCommand
    .run(slug)
    .provide(CaptalConfig.layer, AwsLayers.s3)
```

The `.provide(...)` list must satisfy `FooCommand.Env`. If you forget a layer the compile error will tell you what's missing.

### 7. Test manually

```bash
./mill cli.run foo cafe-centro
```

Or build the assembly and invoke:
```bash
./mill cli.assembly
java -jar out/cli/assembly.dest/out.jar foo cafe-centro
```

## Conventions

### Naming
- File: `<Name>Command.scala` (singular, `PascalCase`).
- Object: `<Name>Command`.
- Main entry: `def run(...): ZIO[Env, CliError, Unit]`.
- Enum case: `<Name>(...args)` — same as object minus the `Command` suffix.

### Output
| Method                   | When                                                |
|--------------------------|-----------------------------------------------------|
| `Output.header(msg)`     | Top-of-section banner (e.g. step 1 of N)            |
| `Output.step(n, total, msg)` | Progress through a multi-step flow             |
| `Output.info(msg)`       | Neutral informational line                          |
| `Output.detail(msg)`     | Dim/sub-info (e.g. "Synced 5 objects")              |
| `Output.success(msg)`    | Success checkmark                                   |
| `Output.warn(msg)`       | Recoverable issue                                   |
| `Output.error(msg)`      | Hard failure (typically wrapped by `tapError`)      |

### Errors
- `CliError.AwsError(operation, cause)` — AWS SDK exceptions.
- `CliError.ConfigError(msg)` — bad YAML, missing dir, etc.
- `CliError.BuildFailed(msg)` — docker/mill subprocess failures.
- `CliError.InvalidVideoPath(path)` — see `VideoCommand` for the file-validation pattern.
- Any other: extend the enum in `CliError.scala` rather than coercing to `InternalError`.

### Updating the CLI version
Adding a public-facing command bumps **minor** (e.g. 1.6.x → 1.7.0). Adding an option to an existing command is **patch**. See skill `bump-cli-version`.

### Skill update
If the command is operator-facing, document it in an existing operator skill (e.g. add to `deploy-location` or create a new one). Update the help text in `--help` output AND the skill — both are user-facing.

## Anti-patterns

- ❌ Calling AWS SDK from raw `ZIO.attempt(...)` without `aws("op")` wrapping — error becomes a useless `Throwable`.
- ❌ Forgetting to add the enum case to the `match` block in `cliApp` — non-exhaustive warning will catch you, but only after compile.
- ❌ Hardcoding region / bucket inside the command — should come from `CaptalConfig` or be a flag.
- ❌ `println` instead of `Output.*` — breaks consistency, no color/emoji.
- ❌ Skipping `withHelp(...)` — `captal foo --help` will show nothing.
- ❌ Asking for layers in `provide()` you don't actually use — wastes AWS connections.
