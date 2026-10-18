# Refactor: fully-qualified names in bodies

**Status:** planned, not started.
**Scope:** 154 uses across 40 files.

## What

Names like `java.time.Instant.now`, `scala.util.Try`, `java.net.URI.create` and
`zio.json.ast.Json.Str` are written out in full at the point of use instead of being imported.
`Assets.scala` is now clean; nothing else is.

```scala
// what is there
val now = java.time.Instant.now.toString
scala.util.Try(java.net.URI.create(url).getPath)

// what it should be
import java.net.URI
import java.time.Instant
import scala.util.Try

val now = Instant.now.toString
Try(URI.create(url).getPath)
```

## Why it is worth doing

Two concrete costs, both observed in this repo rather than assumed.

**It hides shadowing.** `VideoTools.list` had a lambda parameter named `video`, which shadowed the
`core.video` package the body needed for `VideoType`. The compiler reported a missing member on
`AdvertiserVideoRow` — a message about the wrong thing entirely. With `VideoType` imported, the
collision cannot arise.

**It buries the formatter.** scalafmt breaks long qualified chains across lines, so
`scala.util.Try(...)` becomes four lines reading `scala`, `.util`, `.Try:`, and the body. The
sites that are hardest to read are exactly the ones doing something delicate.

## Order

Highest density first; each file is independent and compiles on its own.

| Uses | File |
|---|---|
| 25 | `mcp/test/.../CaptalMcpSpec.scala` |
| 14 | `mcp/test/.../SurveyToolsSpec.scala` |
| 12 | `cli/.../commands/DeprovisionCommand.scala` |
| 11 | `mcp/.../EntityResources.scala` |
| 11 | `mcp/test/.../I18nToolsSpec.scala` |
| 8 | `api/.../Main.scala` |
| 6 | `platform/.../session/SessionService.scala` |
| …34 more, 4 or fewer each |

The test files are the bulk and the safest: `zio.json.ast.Json` accounts for most of it, and a
single `import zio.json.ast.Json` per spec removes it.

## Two that need a decision rather than a rename

`java.time.Duration` collides with `zio.Duration`, which is imported everywhere through `zio.*`.
`Assets.scala` settled it with `import java.time.Duration as JavaDuration`; the same alias should
be used wherever else both appear, rather than inventing a second convention.

`java.lang.System.currentTimeMillis` must stay qualified in any file that also uses ZIO's `System`
— `Tokens.scala` and `Assets.scala` both do. Qualifying it there is correct, not debt.

## How to verify

Mechanical and low-risk: the compiler catches every mistake, since an unimported name does not
resolve. After each file, `mill <module>.compile` and `mill <module>.fix` — scalafix's
`-Wunused:imports` is what catches an import added and then not used.

A sweep to find what is left:

```
grep -rnE '(?<![\w.])(scala\.(util|annotation)|java\.(net|time|util|io|security|nio)|zio\.(json\.ast|schema))\.[A-Za-z]' \
  --include='*.scala' . | grep -v '^.*:import '
```
