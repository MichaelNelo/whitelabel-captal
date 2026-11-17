---
name: add-mcp-tool
description: Use when adding or changing a tool on the MCP backoffice server (mcp/src/.../tools/). Covers a tool as one core command or query through Adapters (runs, runsEither, reads), the flows and queries it finds in the environment, how a refusal reads (Refusals), the @description annotations that are the schema a model reads, scopes, read-only declaration, registration, and the operator skill that has to move with it. Triggers on "add a tool", "new MCP tool", "backoffice tool", "agregar una tool", "nueva herramienta".
version: 2.0.0
---

# Add a tool to the MCP backoffice

A tool is the only way content enters the system: there is no CLI and no provisioning directory.
Every tool that changes something runs one command of `core`, and every tool that reads runs one
query of `core`. The tool never writes a row and never reads a table: the command's handler decides,
`platform`'s event handlers write what it decided, and `platform`'s queries read.

## The shape

Tools live in `mcp/src/whitelabel/captal/mcp/tools/<Area>Tools.scala`, grouped by what they act on
(`LocationTools`, `UnifiTools`, `AdvertiserTools`, `SurveyTools`, `VideoTools`, `I18nTools`,
`AssetTools`, `RecoveryTools`, `TermsTools`, `PreviewTools`, `DeployTools`, `TokenTools`,
`SkillTools`). Add to the existing object for that area rather than making a new one.

A tool says two things — how its input becomes the command or the query, and how the result becomes
its answer — through the extensions in `Adapters`:

```scala
import Adapters.{reads, runs, runsEither}

val create: McpToolHandlerR[OperatorContext & ContentFlows.CreateAdvertiser] = McpTool(
  "create_advertiser")
  .description("Register an advertiser. … Call read_skill('add-advertiser') first.")
  .requireScopes(Auth.ContentWrite)
  .runs((in: CreateRequest) =>
    Clock.instant.map(CreateAdvertiserCommand(AdvertiserId.generate, in.name, in.priority, _))):
    (_, created: Advertiser) => ZIO.succeed(CreateResponse(created.id.asString, created.name, created.priority))

val list: McpToolHandlerR[Queries.ListAdvertisers] = McpTool("list_advertisers")
  .description("Advertisers with their rotation priority …")
  .readOnly
  .reads((in: ListRequest) => ZIO.succeed(ListAdvertisersQuery(in.includeInactive))):
    (_, reports: List[AdvertiserReport]) => ZIO.succeed(ListResponse(reports.map(viewOf)))
```

- **`runs`** builds the command, runs its flow as the operator whose token the call carries
  (`Commands.runAs`, which publishes the `OperatorContext` the event log reads), and turns the result
  into the answer.
- **`runsEither`** is for an input that picks one of two commands answered alike — `set_*_active`
  runs a retirement or a reinstatement, since an event names what happened.
- **`reads`** builds the query, runs it, and turns the report into the answer.

**The answer names the result's type on its parameter** (`created: Advertiser`): that is how the
extension finds the flow — `Flow.Aux[Task, C, Res]` — or the query — `Query.Aux[Task, Q, Res]` — in
the environment. Leave it off and the compiler cannot tell which.

**Both sides are effects**, failing with what the operator reads (`String`):

- The factory reads the clock (`Clock.instant`, never `Instant.now`) and makes the command's ids
  (`AdvertiserId.generate`, `Random.nextUUID`): `core` takes them as arguments. It may read before
  the command — `confirm_video_upload` checks the file arrived in S3 — and it validates what is the
  tool's to validate: file formats (`AssetTools.validateSvg`), a rule's value written as text.
- The answer may add what is not state — a signed upload URL — or read back what the change left,
  through a query (`ZIO.serviceWithZIO[Queries.TermsInForce](Commands.ask(_, …)())`).

A command that creates something answers what it created — its id, the ids of its parts — since the
answer receives only the input and the result.

## Flows and queries live in layers

The tool finds its flow or query in the environment; it never builds one:

- **Flows** are composed once, in `LocationFlows`, `ContentFlows`, `AssetFlows`, `VideoFlows` and
  `OperationFlows`: the handler with the chain that writes and logs its events. A new command adds
  its type alias, its constructor, its layer, and a line in `environment`.
- **Queries** are built once, in `Queries`: `Database` for the ones that read the database alone,
  `Assets` for those that read the bucket too.

Then register the layer in `Main`'s layers, and make sure `CaptalMcp.Env` covers it (it takes the
objects' `All`). The specs build their environment by hand: `ToolsEnvironment.withFlows` (database,
location and content flows, `Queries.Database`) and `DeployE2ESpec`.

## What a refusal reads as

A command or a query refused by `core` answers an `AppError`, which `Refusals.describe` turns into
what the operator reads: what happened and which tool to call. It matches on every case of
`AppError`, each domain's errors described once, so a new domain or a new error is a compile warning
until it is described there — never a `toString` an operator reads. Write the message there, not in
the tool.

The input's own refusals — a malformed MAC, a rule value that does not parse — are the tool's, and
fail the factory with their text. A slug is read with `Refusals.slugOf`, which refuses it as a
command would.

`runs(command, describe)` takes another description only where the text depends on the tool: the
survey question tools come in pairs that name each other (`Refusals.describing(Some(side))`).

## Where the rule goes

Meaning is checked in the command's handler, in `core`, not in the tool: uniqueness, ranges,
references, what a state allows. The handler reads what it decides on through its repositories and
answers every reason it refuses. The tool checks only what `core` does not see: file formats, the
shape of the text an argument arrives as.

## The annotations are code, not comments

Request and response types carry `@description` on the case class and on **every** field:

```scala
final case class ListRequest(
    @description("Retired advertisers are hidden by default; set this to see them too.")
    includeInactive: Boolean = false)
```

That text is the JSON schema a model reads at call time. It is the only thing telling it what the
parameter means, so it decides whether the tool gets called correctly.

This is the one place in the repository where the concision rule does **not** apply. Write as much as
the caller needs — what the value means, what the default does, what happens at zero, which other
tool to use instead. See `docs/context/conventions.adoc`, which carves out this exception explicitly.

The same goes for `.description(...)` on the tool itself. Name the constraints a caller cannot
guess, and point at the operator skill when there is one: `Call read_skill('add-advertiser') first.`

## Scopes and read-only

| Scope | For |
|---|---|
| `Auth.ContentWrite` | locations, advertisers, surveys, videos, assets |
| `Auth.I18nWrite` | translations |
| `Auth.IdentityWrite` | the guest-identity side |
| `Auth.TokensWrite` | issuing operator tokens |
| `Auth.InfraDeploy` | standing a location up |
| `Auth.InfraDestroy` | tearing one down |

A tool that only reads declares `.readOnly` and no scope. A tool that writes declares its scope and
**must not** declare `.readOnly`.

`AnnotationsSpec` enforces both directions — "every read is declared read-only and nothing else is",
and "no tool that requires a write scope claims to be read-only". Get it wrong and that suite fails,
which is the point: a write advertised as read-only is a tool a host may call without asking.

## Written by hand

A few tools do not go through `Adapters`, each for a reason the code says:

- `deploy_location`, `pause_location`, `resume_location`, `destroy_location`: a refusal leads to a
  question to the operator and possibly another command (`DeployTools.start`).
- `configure_unifi`: its answer signs a ticket with the calling token, and `reads` has no context.
- `create_token`, `list_tokens`, `revoke_token`: the backoffice's own security, outside `core`.
- `get_css_schema`, `get_i18n_schema`, `read_skill`: static, with nothing to ask.

## Changing a tool means changing its skill

The operator skills under `mcp/resources/skills/<name>/SKILL.md` are served to the model *by this
same server*. A skill that names a tool that no longer exists is worse than no skill, because the
model obeys it.

So: renaming a tool, changing its parameters, or changing what it refuses means editing its skill in
the same change. This is an invariant, listed in `agents.adoc`.

Those files stay Markdown and stay named `SKILL.md` — `Skills.load` reads `/skills/<slug>/SKILL.md`
off the classpath and publishes `skill://<slug>/SKILL.md`. Renaming them breaks `skills/list`,
`read_skill` and the `skill://` resources.

## Testing

A spec calls a command's tool as an operator, `tool.callWithContext(args, OperatorContext.Default)`,
and a read's with `tool.call(args)`, providing `ToolsEnvironment.withFlows`. The command's own rules
are tested in `core`, against fake repositories (see skill `add-command`).

## Checklist

1. The command or query exists in `core`, with its handler or report (skill `add-command`).
2. Its flow or query is in a layer (`*Flows`, `Queries`), registered in `Main` and covered by
   `CaptalMcp.Env` and the specs' environments.
3. The tool in the right `<Area>Tools.scala`, through `runs`, `runsEither` or `reads`, the answer
   naming the result's type.
4. `@description` on the request/response classes and every field.
5. `.readOnly` **or** `.requireScopes(...)`, never both.
6. Add it to that object's `all`. Nothing else registers it; if it is not in `all`, it does not exist.
7. A new error described in `Refusals`.
8. Update the operator skill that mentions it, in the same change.
9. `mill mcp.test` — `AnnotationsSpec` checks the read-only/scope pairing; the area's own spec
   checks behaviour.

## Anti-patterns

- ❌ Reading or writing a table from a tool — `run(query[…])` in a tool is a command or a query that
  is missing.
- ❌ Building a flow or a query in the tool (`ListAdvertisers(quill)`) — find it in the environment.
- ❌ A refusal's text in the tool — it goes in `Refusals`, once for every tool that meets it.
- ❌ `Instant.now` or an id made outside the factory — the factory is where the command's instant and
  ids come from.
- ❌ Writing a terse `@description` to match the repository's comment style — it is the schema, and
  terse here means a model guessing.
- ❌ `.readOnly` on anything that writes, to avoid a consent prompt.
- ❌ Adding a tool and leaving its operator skill describing the old parameters.
- ❌ Renaming a `SKILL.md` or moving it out of `mcp/resources/skills/<slug>/`.
- ❌ Creating a new `<Area>Tools.scala` for a tool that belongs to an existing area.
