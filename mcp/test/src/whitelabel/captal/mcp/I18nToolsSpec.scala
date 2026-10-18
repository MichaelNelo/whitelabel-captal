package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.ToolContent
import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.mcp.tools.I18nTools
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocalizedTextRow, LocationRow, RqliteDataSource}
import zio.*
import zio.test.*

/** Exercises the tools against a real rqlite instance.
  *
  * The two behaviours worth proving are exactly the ones `EntityWriter` gets wrong: that the upsert
  * keys on `(entity_id, locale, location_id)` rather than on the primary key — which is what the
  * unique index from V12 actually enforces — and that writing a retired translation brings it back
  * instead of colliding.
  */
object I18nToolsSpec extends ZIOSpecDefault:
  private val Slug = "mcp-test-location"
  private val Locale = "es"
  private val SomeKey = "welcome.title"
  private val OtherKey = "welcome.subtitle"

  private def resetFixture: ZIO[QuillSqlite, Throwable, String] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      val locationId = UUID.randomUUID.toString
      val now = java.time.Instant.EPOCH.toString
      for
        _ <- quill.run(query[LocalizedTextRow].filter(_.category == "frontend").delete)
        _ <- quill.run(query[LocationRow].filter(_.slug == lift(Slug)).delete)
        _ <- quill.run(
          query[LocationRow].insertValue(
            lift(
              LocationRow(
                id = locationId,
                slug = Slug,
                name = "MCP test",
                isActive = 1,
                createdAt = now,
                updatedAt = now,
                unifiApMac = None))))
      yield locationId

  private def rowsFor(locationId: String): ZIO[QuillSqlite, Throwable, List[LocalizedTextRow]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      quill.run(query[LocalizedTextRow].filter(_.locationId == lift(Option(locationId))))

  private def set(key: String, value: String) = I18nTools
    .setTranslations
    .call(
      Some(
        zio
          .json
          .ast
          .Json
          .Obj(
            Chunk(
              "slug"    -> zio.json.ast.Json.Str(Slug),
              "locale"  -> zio.json.ast.Json.Str(Locale),
              "entries" ->
                zio
                  .json
                  .ast
                  .Json
                  .Arr(
                    Chunk(
                      zio
                        .json
                        .ast
                        .Json
                        .Obj(
                          Chunk(
                            "key"   -> zio.json.ast.Json.Str(key),
                            "value" -> zio.json.ast.Json.Str(value)))))
            ))))

  /** Blind insert with a caller-chosen id, bypassing the natural-key lookup the tools do. Exists
    * only to characterise what the driver does on a constraint violation.
    */
  private def insertRaw(
      locationId: String,
      key: String,
      value: String,
      id: String): ZIO[QuillSqlite, Throwable, Long] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    val now = java.time.Instant.EPOCH.toString
    quill.run(
      query[LocalizedTextRow].insertValue(
        lift(LocalizedTextRow(id, key, Locale, value, "frontend", now, now, Some(locationId), 1))))
  private val quillLayer =
    RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  def spec =
    suite("I18nTools against rqlite")(
      test("writing the same key twice updates in place instead of colliding on the unique index"):
        for
          locationId <- resetFixture
          first      <- set(SomeKey, "Bienvenido")
          second     <- set(SomeKey, "Hola")
          rows       <- rowsFor(locationId)
          forKey = rows.filter(r => r.entityId == SomeKey && r.locale == Locale)
        yield assertTrue(
          !first.isError.contains(true),
          // The one that would fail with onConflictUpdate(_.id) and a surrogate id.
          !second.isError.contains(true),
          forKey.size == 1,
          forKey.head.value == "Hola"
        )
      ,
      test("removing retires the row, and writing again revives the same row"):
        for
          locationId <- resetFixture
          _          <- set(SomeKey, "Bienvenido")
          removed    <- I18nTools
            .removeTranslation
            .call(
              Some(
                zio
                  .json
                  .ast
                  .Json
                  .Obj(
                    Chunk(
                      "slug"   -> zio.json.ast.Json.Str(Slug),
                      "locale" -> zio.json.ast.Json.Str(Locale),
                      "key"    -> zio.json.ast.Json.Str(SomeKey)))))
          afterRemove <- rowsFor(locationId)
          _           <- set(SomeKey, "De nuevo")
          afterRevive <- rowsFor(locationId)
        yield assertTrue(
          !removed.isError.contains(true),
          afterRemove.count(_.entityId == SomeKey) == 1,
          afterRemove.find(_.entityId == SomeKey).exists(_.isActive == 0),
          // Same row revived, not a second one inserted alongside it.
          afterRevive.count(_.entityId == SomeKey) == 1,
          afterRevive
            .find(_.entityId == SomeKey)
            .exists(r => r.isActive == 1 && r.value == "De nuevo")
        )
      ,
      test("a key outside the schema is rejected and nothing is written"):
        for
          locationId <- resetFixture
          result     <- set("welcome.doesNotExist", "x")
          rows       <- rowsFor(locationId)
        yield assertTrue(
          result.isError.contains(true),
          rows.isEmpty,
          result
            .content
            .exists {
              case ToolContent.Text(text, _) =>
                // Names the offending key and where the legal ones are. That used to be the
                // `i18n://` URI, and a failure message is the worst place to send someone to a
                // door that may not open for them.
                text.contains("welcome.doesNotExist") && text.contains("get_i18n_schema")
              case _ =>
                false
            }
        )
      ,
      test("status reports what the locale is still missing"):
        for
          _      <- resetFixture
          _      <- set(SomeKey, "Bienvenido")
          _      <- set(OtherKey, "Conectate")
          result <- I18nTools
            .status
            .call(Some(zio.json.ast.Json.Obj(Chunk("slug" -> zio.json.ast.Json.Str(Slug)))))
          rendered =
            result
              .content
              .collect { case ToolContent.Text(t, _) =>
                t
              }
              .mkString
        yield assertTrue(
          !result.isError.contains(true),
          rendered.contains("\"present\":2"),
          // Every key except the two just written.
          rendered.contains("welcome.steps.step1"),
          !rendered.contains("\"missing\":[]")
        )
      ,
      test("a unique-constraint violation reaches JDBC as an error"):
        // This asserted the opposite until the driver bump, and it said so: "rqlite reports the
        // failure as a per-statement `error` field inside an HTTP 200 response, and the JDBC driver
        // does not translate that into a SQLException. If a future driver starts surfacing it, this
        // test fails and the workaround can be revisited."
        //
        // One did, and it did. `rqlite-jdbc` 10.5.2.1 states it outright: "failed statements,
        // batches and commits now throw instead of being silently ignored".
        //
        // What the old version pinned was a bug in a dependency, held as a contract. Four other
        // things here were written against the same silence and three were broken by it: a
        // reflection proxy whose own exception handling had never once run, migration V21 reporting
        // success on a statement SQLite 3.53 refuses, and `DeviceRow` writing to a table that does
        // not exist — on every session since V6, found only because this driver finally said so.
        //
        // The validation each write tool does in Scala still earns its place: a tool that names
        // which key collided beats one that forwards a constraint name. What changed is that the
        // schema is a backstop now rather than a silence.
        for
          locationId <- resetFixture
          _          <- insertRaw(locationId, SomeKey, "uno", "id-a")
          outcome    <- insertRaw(locationId, SomeKey, "dos", "id-b").either
          rows       <- rowsFor(locationId)
          forKey = rows.filter(_.entityId == SomeKey)
        yield assertTrue(
          // Asserted on the message rather than the type alone: the point is that the reason
          // survives the trip out of rqlite's HTTP 200 into the exception, which is what makes it
          // diagnosable at all.
          outcome
            .left
            .toOption
            .exists(e => Option(e.getMessage).exists(_.contains("UNIQUE constraint failed"))),
          // And the write is still gone, which was true before and is the half that mattered.
          forKey.size == 1,
          forKey.head.value == "uno"
        )
      // The schema is laid down once by `mcp.test` before forking — see `migratedRqliteUrl` in
      // build.mill. A Flyway run per spec raced.
    ).provideShared(quillLayer) @@ TestAspect.sequential
end I18nToolsSpec
