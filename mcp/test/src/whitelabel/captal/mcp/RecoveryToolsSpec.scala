package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.mcp.tools.{LocationTools, RecoveryTools}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRecoveryRow, LocationRow, RqliteDataSource}
import zio.*
import zio.json.ast.Json
import zio.test.*

/** RecoveryTools against rqlite: what a location reports before and after it sets anything, and
  * what is refused before a write.
  *
  * Rows are scoped to a slug prefix, as in [[LocationToolsSpec]]: suites share one rqlite instance.
  */
object RecoveryToolsSpec extends ZIOSpecDefault:
  private val Prefix = "recovery-spec"

  private def locations
      : ZIO[QuillSqlite, Throwable, List[LocationRow]] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    quill.run(query[LocationRow]).map(_.filter(_.slug.startsWith(Prefix)))

  private def stored(slug: String): ZIO[QuillSqlite, Throwable, Option[LocationRecoveryRow]] =
    locations.flatMap: owned =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        val id = owned.find(_.slug == slug).map(_.id).getOrElse("")
        quill.run(query[LocationRecoveryRow].filter(_.locationId == lift(id))).map(_.headOption)

  private def clear: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    locations.flatMap: owned =>
      ZIO.foreachDiscard(owned.map(_.id)): id =>
        quill.run(query[LocationRecoveryRow].filter(_.locationId == lift(id)).delete) *>
          quill.run(query[LocationRow].filter(_.id == lift(id)).delete)

  private def json(fields: (String, Json)*) = Some(Json.Obj(Chunk.fromIterable(fields)))

  private def str(s: String) = Json.Str(s)

  /** A `BigDecimal`, so a whole number is written without a fraction and reads as an `Int`. */
  private def num(n: BigDecimal) = Json.Num(n)

  private def textOf(result: CallToolResult): String =
    result
      .content
      .collect { case ToolContent.Text(t, _) =>
        t
      }
      .mkString

  private def create(slug: String) = LocationTools
    .create
    .call(json("slug" -> str(slug), "name" -> str(slug)))

  private def get(slug: String) = RecoveryTools.status.call(json("slug" -> str(slug)))

  private def set(slug: String, fields: (String, Json)*) = RecoveryTools
    .set
    .call(json(("slug" -> str(slug)) +: fields*))

  private def reset(names: String*) = "reset" -> Json.Arr(Chunk.fromIterable(names.map(str)))

  def spec =
    suite("RecoveryTools against rqlite")(
      test("a location that set nothing reports every default, and says they are defaults"):
        val slug = s"$Prefix-bare"
        for
          _      <- clear
          _      <- create(slug)
          bare   <- get(slug)
          stored <- stored(slug)
        yield assertTrue(
          !bare.isError.contains(true),
          textOf(bare).contains("\"maximumSeconds\":300"),
          textOf(bare).contains("\"ceilingSeconds\":1800"),
          textOf(bare).contains("\"recurrentFactor\":0.1"),
          textOf(bare).contains("\"recoveryWindowSeconds\":2592000"),
          textOf(bare).contains("\"playbackGraceSeconds\":600"),
          textOf(bare).contains("\"maximumSeconds\",\"ceilingSeconds\""),
          // Reading writes nothing.
          stored.isEmpty
        )
      ,
      test("a change keeps what it does not name, and a reset returns a setting to its default"):
        val slug = s"$Prefix-short"
        for
          _     <- clear
          _     <- create(slug)
          first <- set(slug, "maximumSeconds" -> num(60), "playbackGraceSeconds" -> num(30))
          row   <- stored(slug)
          more  <- set(slug, "recurrentFactor" -> num(0.5))
          back  <- set(slug, reset("maximumSeconds"))
          after <- stored(slug)
          read  <- get(slug)
        yield assertTrue(
          !first.isError.contains(true),
          textOf(first).contains("\"maximumSeconds\":60"),
          textOf(first).contains("\"playbackGraceSeconds\":30"),
          !textOf(first).contains("\"defaults\":[\"maximumSeconds\""),
          // The row, so a write rqlite rejected cannot pass for one that happened.
          row.exists(r => r.maximumSeconds.contains(60) && r.playbackGraceSeconds.contains(30)),
          row.exists(_.ceilingSeconds.isEmpty),
          textOf(more).contains("\"maximumSeconds\":60"),
          textOf(more).contains("\"recurrentFactor\":0.5"),
          textOf(back).contains("\"maximumSeconds\":300"),
          textOf(back).contains("\"playbackGraceSeconds\":30"),
          after.exists(r => r.maximumSeconds.isEmpty && r.recurrentFactor.contains(0.5)),
          textOf(read) == textOf(back)
        )
        end for
      ,
      test("a change that makes no sense is refused, and nothing is written"):
        val slug = s"$Prefix-refused"
        for
          _        <- clear
          _        <- create(slug)
          _        <- set(slug, "maximumSeconds" -> num(60))
          negative <- set(slug, "videoWeight" -> num(-1))
          capped   <- set(slug, "maximumSeconds" -> num(3600))
          unknown  <- set(slug, reset("maximum"))
          both     <- set(slug, "maximumSeconds" -> num(90), reset("maximumSeconds"))
          nothing  <- set(slug)
          missing  <- set(s"$Prefix-nope", "maximumSeconds" -> num(60))
          row      <- stored(slug)
        yield assertTrue(
          negative.isError.contains(true),
          textOf(negative).contains("videoWeight cannot be negative"),
          capped.isError.contains(true),
          textOf(capped).contains("below maximumSeconds"),
          unknown.isError.contains(true),
          textOf(unknown).contains("No setting is named maximum"),
          both.isError.contains(true),
          textOf(both).contains("both set and reset"),
          nothing.isError.contains(true),
          missing.isError.contains(true),
          textOf(missing).contains("No location"),
          row.exists(r => r.maximumSeconds.contains(60) && r.videoWeight.isEmpty)
        )
        end for
      // The schema is laid down once by `mcp.test` before forking — see `migratedRqliteUrl` in
      // build.mill.
    ).provideShared(
      RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)) @@
      TestAspect.sequential
end RecoveryToolsSpec
