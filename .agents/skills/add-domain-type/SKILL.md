---
name: add-domain-type
description: Use when adding a new domain type — opaque type Id, value class, refined string, or enum — to core/. Covers the opaque type pattern, decoder wiring for Quill and zio-schema, and cross-compilation constraints. Triggers on "add domain type", "opaque type", "new id type", "value class", "agregar tipo dominio".
version: 1.1.0
---

# Add a domain type

## Mental model

`core/` contains the **pure domain model** cross-compiled to JVM (for the API) and JS (for the SPA validation logic). Types here have three constraints:

1. **No JVM-only deps** — `java.util.UUID` and `java.time.Instant` work; `java.io.*`, `java.nio.*`, `javax.*` don't.
2. **Domain-precise** — prefer `opaque type Email = String` over raw `String`, so you can't accidentally pass an unvalidated address.
3. **Cross-codable** — every domain type that crosses a boundary (HTTP wire, DB) needs explicit codecs: `circe.{Encoder,Decoder}` for JSON, `zio.schema.Schema` for the server's own wire format, `Quill.MappedEncoding` for DB columns.

## Steps

### 1. Pick the right pattern

| Need                                     | Pattern                                                     |
|------------------------------------------|-------------------------------------------------------------|
| Unique identifier (UUID-backed)          | `opaque type Id = UUID` + companion (`apply`, `generate`, `fromString`, `unsafe`)  |
| Validated string (email, slug, mac)      | `opaque type Email = String` + companion (`fromString: Either[String, Email]`)     |
| Sum of fixed alternatives                | `enum Foo: case A, B, C`                                    |
| Tagged sub-type (e.g. session vs user id)| Two separate `opaque type` — distinct types prevent mix-up  |
| Simple data carrier                      | `final case class ...` — no opaque, no codec ceremony beyond `Encoder.AsObject.derived`/`Decoder.derived`  |

Stick to the patterns already used in `core/user/Id.scala` (which holds `Id`, `SessionId`, `DeviceId` and `Email` — one file per aggregate, not one per type), `core/survey/Id.scala`, `core/application/phase.scala`.

### 2. Create the file

Convention: one file per type (or family of related types). Path: `core/src/whitelabel/captal/core/<aggregate>/<TypeName>.scala`.

**Opaque UUID id**:

```scala
package whitelabel.captal.core.bonus

import java.util.UUID

opaque type Id = UUID
object Id:
  def apply(value: UUID): Id = value
  def generate: Id = UUID.randomUUID()
  def fromString(s: String): Option[Id] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): Id = UUID.fromString(s)

  extension (id: Id)
    def value: UUID = id
    def asString: String = id.toString
```

Why all four constructors:
- `apply(uuid)` — wrap a known UUID.
- `generate` — fresh id for new entities.
- `fromString` — safe parse from external input (HTTP body, CLI arg).
- `unsafe` — Quill / serde paths where the string is already trusted.

**Validated string**:

```scala
package whitelabel.captal.core.bonus

opaque type Code = String
object Code:
  private val pattern = "^[A-Z0-9]{6}$".r

  def fromString(value: String): Either[String, Code] =
    if pattern.matches(value) then Right(value)
    else Left(s"Invalid bonus code format: $value")

  def unsafeFrom(value: String): Code = value

  extension (code: Code)
    def value: String = code
```

**Enum**:

```scala
package whitelabel.captal.core.bonus

enum Tier:
  case Bronze, Silver, Gold
```

Match exhaustively at use sites — compiler enforces.

### 3. Wire Quill MappedEncoding (if the type goes to a DB column)

The `Row` case class uses your domain type directly (`case class BonusRow(id: bonus.Id, ...)`). Quill needs to encode/decode opaque types to/from the underlying primitive.

Add to `platform/src/whitelabel/captal/platform/schema/core.scala` (or a new sibling file if the aggregate is large):

```scala
import scala.annotation.targetName

import io.getquill.MappedEncoding
import whitelabel.captal.core.bonus

@targetName("bonusIdToString")
inline given MappedEncoding[bonus.Id, String] = MappedEncoding(_.asString)
@targetName("stringToBonusId")
inline given MappedEncoding[String, bonus.Id] = MappedEncoding(bonus.Id.unsafe)

@targetName("bonusCodeToString")
inline given MappedEncoding[bonus.Code, String] = MappedEncoding(_.value)
@targetName("stringToBonusCode")
inline given MappedEncoding[String, bonus.Code] = MappedEncoding(bonus.Code.unsafeFrom)

@targetName("bonusTierToString")
inline given MappedEncoding[bonus.Tier, String] = MappedEncoding(_.toString)
@targetName("stringToBonusTier")
inline given MappedEncoding[String, bonus.Tier] = MappedEncoding(bonus.Tier.valueOf)
```

Both directions are needed (encode = domain → DB, decode = DB → domain). Use the `unsafe`/`unsafeFrom` constructors here — Quill is reading data WE wrote, so format issues mean the DB is corrupt, not user input.

The `@targetName` on every one of these is not optional. An opaque type erases to its underlying
type, so `MappedEncoding[bonus.Id, String]` and `MappedEncoding[bonus.Code, String]` would both
erase to `MappedEncoding[String, String]` and collide on the synthetic name the compiler generates —
a new mapped encoding without a unique `@targetName` is a double-definition error, not a silent
shadow.

If you forget one direction, the error is "No Decoder/Encoder found for X" at compile time of any Quill query touching the column.

### 4. Wire a zio-schema `Schema` (if the type crosses HTTP)

There is no tapir and no `endpoints/` module. The server's wire format is zio-schema, and a plain
`opaque type` erases to its underlying type, so `DeriveSchema.gen` can't see through it — the
instance has to be named and written by hand, the same way `MappedEncoding` above is.

Where it goes depends on what the type is for:

- If the type belongs to the survey question tree (a new `QuestionType`, rule, or id used inside
  `QuestionToAnswer`/`AnswerValue`), add it to `platform/src/whitelabel/captal/platform/surveySchemas.scala`
  — this is the one definition both `api`'s responses and the MCP backoffice's writes to
  `question_rules.rule_config`/`answers.answer_value` share.
- For anything else that rides in a request or response body, add it to
  `api/src/whitelabel/captal/api/wire.scala`, next to the type's circe instance.

```scala
import zio.schema.Schema
import whitelabel.captal.core.bonus

given Schema[bonus.Id] = Schema[String].transformOrFail(
  text => bonus.Id.fromString(text).toRight(s"Not a bonus id: $text"),
  id => Right(id.asString))

given Schema[bonus.Code] = Schema[String].transformOrFail(bonus.Code.fromString, c => Right(c.value))

given Schema[bonus.Tier] = Schema[String].transform(
  str => bonus.Tier.valueOf(str),  // or a safe lookup if the enum shouldn't throw
  _.toString)
```

`transformOrFail` is what makes a malformed value decode to an error rather than throwing; use it
for anything a request body could carry unvalidated. `transform` (no `Fail`) is only for a
direction that cannot fail, such as rendering a trusted enum back to its name.

### 5. Wire circe Encoder/Decoder (if the type is in a JSON DTO)

Not the same place as step 4: `platform/surveySchemas.scala` and `api/wire.scala` hold zio-schema
instances only. A circe instance goes beside the type itself, in a `codecs.scala` file in the same
`core/` package (see `core/survey/question/codecs.scala` for the established pattern), or in
`messages/` if the type only exists as a wire DTO field:

```scala
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.bonus

given Encoder[bonus.Id] = Encoder.encodeString.contramap(_.asString)
given Decoder[bonus.Id] = Decoder.decodeString.emap(s =>
  bonus.Id.fromString(s).toRight(s"Invalid Id: $s"))

given Encoder[bonus.Code] = Encoder.encodeString.contramap(_.value)
given Decoder[bonus.Code] = Decoder.decodeString.emap(bonus.Code.fromString)
```

For a product type (a case class, not an opaque type), derive both directions the way every other
type in `codecs.scala` does — `Encoder.AsObject.derived` / `Decoder.derived` — rather than the
semi-auto `deriveEncoder`/`deriveCodec` helpers, which are not this codebase's convention. For a
plain enum like `bonus.Tier`, write it by hand the same shape as `CommonRule` or `HierarchyLevel` in
`core/survey/question/codecs.scala`.

### 6. Test (optional but recommended for validated strings)

`core/test/.../<aggregate>/<Type>Spec.scala`:

```scala
test("Code.fromString rejects lowercase") {
  assertTrue(Code.fromString("abc123").isLeft)
}

test("Code.fromString accepts 6-char uppercase alphanumeric") {
  assertTrue(Code.fromString("ABC123").isRight)
}
```

For opaque Id types backed by UUID the tests are usually trivial; skip unless there's domain logic in the companion.

## Cross-compile gotchas

- **`java.time.Instant`** works in Scala.js via `scala-java-time` (already a dep of `client`). Safe to use.
- **`java.util.UUID`** has a Scala.js polyfill — safe.
- **`scala.util.Try`** works on both — safe.
- **`Regex`** works — safe.
- **`java.io.*`, `java.nio.*`, `java.net.URL`** — JVM only, will break Scala.js compile.
- **No `java.util.concurrent.*`, no threading primitives** — domain types should be effect-free.

## Domain organization

```
core/src/whitelabel/captal/core/
├── <aggregate>/
│   ├── Id.scala          # opaque type Id, SessionId, DeviceId, ...
│   ├── State.scala       # opaque type State + sub-states (Initial, WithEmail, ...)
│   ├── Event.scala       # enum <Aggregate>Event
│   ├── Error.scala       # enum <Aggregate>Error
│   ├── <Aggregate>.scala # case class wrapping (id, state)
│   └── ops.scala         # pure transitions: f(state, input) → Either[Error, Event]
└── application/
    ├── Event.scala       # outer Event sum wrapping all aggregates
    ├── Error.scala       # outer Error sum
    └── ...
```

Follow this layout when adding a new aggregate. Less is more — only add what's actually used.

## Anti-patterns

- ❌ `final case class UserId(value: UUID)` — allocates a JVM object per id; opaque type is zero-cost.
- ❌ `type Id = UUID` (no opaque) — leaks the underlying type, kills the type safety win.
- ❌ Validation in the constructor of a case class (`require(...)`) — throws at construction; prefer `fromString: Either` and force the caller to handle.
- ❌ Mixing the `unsafe` constructor with user input — that's what `fromString` is for; `unsafe` is for trusted internal data (DB rows, deserialized JSON that already passed Decoder).
- ❌ Adding a single domain type and forgetting the Quill / zio-schema / circe codecs — compile errors at use sites are confusing ("No Decoder for X"); easier to wire all three upfront.
- ❌ `import java.io.File` in `core/` — breaks Scala.js. Use abstract `Path` or `String` and resolve at the JVM edge.
