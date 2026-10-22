---
name: add-mcp-tool
description: Use when adding or changing a tool on the MCP backoffice server (mcp/src/.../tools/). Covers the McpTool builder, the @description annotations that are the schema a model reads, scopes, read-only declaration, registration, and the operator skill that has to move with it. Triggers on "add a tool", "new MCP tool", "backoffice tool", "agregar una tool", "nueva herramienta".
version: 1.0.0
---

# Add a tool to the MCP backoffice

The backoffice exposes 31 tools. A tool is the only way content enters the system: there is no CLI
and no provisioning directory.

## The shape

Tools live in `mcp/src/whitelabel/captal/mcp/tools/<Area>Tools.scala`, grouped by what they act on
(`AdvertiserTools`, `LocationTools`, `SurveyTools`, `VideoTools`, `I18nTools`, `AssetTools`,
`TokenTools`, `UnifiTools`, `DeployTools`, `SkillTools`). Add to the existing object for that area
rather than making a new one.

```scala
val create: McpToolHandlerR[QuillSqlite] = McpTool("create_advertiser")
  .description(
    "Register an advertiser. Priority weights the video rotation against the other active advertisers; zero means never drawn. Names must be unique. Call read_skill('add-advertiser') first.")
  .requireScopes(Auth.ContentWrite)
  .handle: (in: CreateRequest) =>
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      ...
```

Then add it to that object's `all`, which is what `CaptalMcp` concatenates:

```scala
val all: List[McpToolHandlerR[QuillSqlite]] = List(list, create, update, setActive)
```

Nothing else registers it. If it is not in `all`, it does not exist.

## The annotations are code, not comments

Request and response types carry `@description` on the case class and on **every** field:

```scala
final case class ListRequest(
    @description("Retired advertisers are hidden by default; set this to see them too.")
    includeInactive: Boolean = false)
```

That text is the JSON schema a model reads at call time. It is the only thing telling it what the
parameter means, so it decides whether the tool gets called correctly.

This is the one place in the repository where the concision rule does **not** apply. Everywhere else
a comment says what something takes and returns, tersely. Here, write as much as the caller needs —
what the value means, what the default does, what happens at zero, which other tool to use instead.
See `docs/context/conventions.adoc`, which carves out this exception explicitly.

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

## Validate in the tool, not only in the schema

The schema constrains types, not meaning. Uniqueness, ranges and references are checked in the
handler, and the failure message says what to do instead:

```scala
_ <- ZIO.when(clashes.nonEmpty)(
       ZIO.fail(s"An advertiser named '$name' already exists (id ${clashes.head.id}). Use update_advertiser to change it."))
```

Enforce it even when the table looks like it would: `advertisers` has no unique index, so nothing
below this would reject a duplicate.

Writes go through the verification helper rather than trusting a row count — the database can refuse
a statement inside a response that otherwise looks successful. See `Writes.expectOne`.

## Changing a tool means changing its skill

The operator skills under `mcp/resources/skills/<name>/SKILL.md` are served to the model *by this
same server*. A skill that names a tool that no longer exists is worse than no skill, because the
model obeys it.

So: renaming a tool, changing its parameters, or changing what it refuses means editing its skill in
the same change. This is an invariant, listed in `agents.adoc`.

Those files stay Markdown and stay named `SKILL.md` — `Skills.load` reads `/skills/<slug>/SKILL.md`
off the classpath and publishes `skill://<slug>/SKILL.md`. Renaming them breaks `skills/list`,
`read_skill` and the `skill://` resources.

## Checklist

1. Add the tool to the right `<Area>Tools.scala`.
2. `@description` on the request/response classes and every field.
3. `.readOnly` **or** `.requireScopes(...)`, never both.
4. Add it to that object's `all`.
5. Validate meaning in the handler, with a message naming the alternative.
6. Update the operator skill that mentions it, in the same change.
7. `mill mcp.test` — `AnnotationsSpec` checks the read-only/scope pairing; the area's own spec
   checks behaviour.

## Anti-patterns

- ❌ Writing a terse `@description` to match the repository's comment style — it is the schema, and
  terse here means a model guessing.
- ❌ `.readOnly` on anything that writes, to avoid a consent prompt.
- ❌ Adding a tool and leaving its operator skill describing the old parameters.
- ❌ Relying on a database constraint that is not there. Check the schema before assuming uniqueness
  is enforced.
- ❌ Renaming a `SKILL.md` or moving it out of `mcp/resources/skills/<slug>/`.
- ❌ Creating a new `<Area>Tools.scala` for a tool that belongs to an existing area.
