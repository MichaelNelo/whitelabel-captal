package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.mcp.tools.{I18nTools, LocationTools, UnifiTools}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRow, RqliteDataSource}
import zio.*
import zio.test.*

import QuillArrow.->

/** LocationTools against rqlite. `locations.slug` is UNIQUE and enforced, so what these pin is the
  * diagnosis the Scala pre-check adds, not the integrity.
  *
  * Rows are scoped to a slug prefix rather than cleared wholesale: suites run in parallel against
  * one rqlite instance and other specs keep locations of their own.
  */
object LocationToolsSpec extends ZIOSpecDefault:
  private val Prefix = "loc-spec"

  /** Filtered in Scala rather than SQL: Quill's SQLite dialect has no `startsWith`. */
  private def rows
      : ZIO[QuillSqlite, Throwable, List[LocationRow]] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    quill.run(query[LocationRow]).map(_.filter(_.slug.startsWith(Prefix)))

  private def clear: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    rows.flatMap: owned =>
      ZIO.foreachDiscard(owned.map(_.slug)): slug =>
        quill.run(query[LocationRow].filter(_.slug == lift(slug)).delete)

  private def json(fields: (String, zio.json.ast.Json)*) = Some(
    zio.json.ast.Json.Obj(Chunk.fromIterable(fields)))

  private def str(s: String) = zio.json.ast.Json.Str(s)

  private def textOf(result: CallToolResult): String =
    result
      .content
      .collect { case ToolContent.Text(t, _) =>
        t
      }
      .mkString

  private def create(slug: String, name: String) = LocationTools
    .create
    .call(json("slug" -> str(slug), "name" -> str(name)))

  private def deactivate(slug: String): ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    quill.run(query[LocationRow].filter(_.slug == lift(slug)).update(_.isActive -> 0)).unit

  def spec =
    suite("LocationTools against rqlite")(
      test("creating twice with the same slug is refused, and the second write never happens"):
        for
          _      <- clear
          first  <- create(s"$Prefix-cafe", "Café Centro")
          second <- create(s"$Prefix-cafe", "Another Café")
          stored <- rows
        yield assertTrue(
          !first.isError.contains(true),
          second.isError.contains(true),
          textOf(second).contains("already exists"),
          stored.size == 1,
          stored.head.name == "Café Centro"
        )
      ,
      test("a slug that is not kebab-case is refused before any write"):
        for
          _       <- clear
          spaces  <- create(s"$Prefix cafe", "Spaces")
          capital <- create(s"$Prefix-Cafe", "Capital")
          under   <- create(s"${Prefix}_cafe", "Underscore")
          stored  <- rows
        yield assertTrue(
          spaces.isError.contains(true),
          capital.isError.contains(true),
          under.isError.contains(true),
          textOf(capital).contains("kebab-case"),
          stored.isEmpty)
      ,
      test("hyphens at the edges or doubled are refused — stricter than the old provisioning rule"):
        for
          _ <- clear
          // All three match a plain `[a-z0-9-]+`.
          leading  <- create(s"-$Prefix", "Leading")
          trailing <- create(s"$Prefix-", "Trailing")
          doubled  <- create(s"$Prefix--cafe", "Doubled")
          stored   <- rows
        yield assertTrue(
          leading.isError.contains(true),
          trailing.isError.contains(true),
          doubled.isError.contains(true),
          stored.isEmpty)
      ,
      // Outside the prefix on purpose, so `rows` cannot see them: what is asserted is the refusal,
      // read back by slug.
      test("the prefixes every location shares are refused as slugs"):
        for
          ui       <- create("ui", "Scripts")
          bundle   <- create("bundle", "Bundle")
          releases <- create("releases", "Releases")
          written  <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            quill
              .run(query[LocationRow])
              .map(_.filter(row => Set("ui", "bundle", "releases").contains(row.slug)))
        yield assertTrue(
          ui.isError.contains(true),
          bundle.isError.contains(true),
          releases.isError.contains(true),
          textOf(ui).contains("reserved"),
          written.isEmpty)
      ,
      test("a slug too long for the ALB target group name is refused, and the message says why"):
        for
          _ <- clear
          // 26 characters: one past what 'captal-' leaves of the 32-character AWS limit.
          result <- create(s"$Prefix-" + "x" * (26 - Prefix.length - 1), "Too long")
          stored <- rows
        yield assertTrue(
          result.isError.contains(true),
          textOf(result).contains("25"),
          textOf(result).contains("captal-"),
          stored.isEmpty)
      ,
      test("a slug of exactly the maximum length is accepted"):
        for
          _ <- clear
          slug = s"$Prefix-" + "x" * (25 - Prefix.length - 1)
          result <- create(slug, "Exactly at the limit")
          stored <- rows
        yield assertTrue(
          slug.length == 25,
          !result.isError.contains(true),
          stored.map(_.slug) == List(slug))
      ,
      test("a blank name is refused"):
        for
          _      <- clear
          result <- create(s"$Prefix-blank", "   ")
          stored <- rows
        yield assertTrue(result.isError.contains(true), stored.isEmpty)
      ,
      test("a created location is immediately usable by the tools that take a slug"):
        val slug = s"$Prefix-usable"
        for
          _      <- clear
          _      <- create(slug, "Usable")
          status <- I18nTools.status.call(json("slug" -> str(slug)))
        yield assertTrue(
          // The location resolves; every other tool answers "No location with slug" otherwise.
          !status.isError.contains(true),
          !textOf(status).contains("No location"),
          textOf(status).contains(s"\"slug\":\"$slug\""),
          // No locale has a single key yet.
          textOf(status).contains("\"locales\":[]")
        )
      ,
      test("the listing reports the slug and hides deactivated locations unless asked"):
        val live = s"$Prefix-live"
        val gone = s"$Prefix-gone"
        for
          _      <- clear
          _      <- create(live, "Live")
          _      <- create(gone, "Gone")
          _      <- deactivate(gone)
          listed <- LocationTools.list.call(json())
          all    <- LocationTools.list.call(json("includeInactive" -> zio.json.ast.Json.Bool(true)))
        yield assertTrue(
          textOf(listed).contains(live),
          !textOf(listed).contains(gone),
          textOf(all).contains(gone),
          // Nothing configures UniFi at creation, and the listing has to say so.
          textOf(listed).contains("\"unifiConfigured\":false")
        )
      ,
      test("UniFi calls are turned off and back on per location, and get_unifi_config says so"):
        val slug = s"$Prefix-unifi"
        def set(target: String, enabled: Boolean) = UnifiTools
          .setEnabled()
          .call(json("slug" -> str(target), "enabled" -> zio.json.ast.Json.Bool(enabled)))
        for
          _       <- clear
          _       <- create(slug, "UniFi")
          initial <- UnifiTools.status().call(json("slug" -> str(slug)))
          off     <- set(slug, false)
          stored  <- rows
          after   <- UnifiTools.status().call(json("slug" -> str(slug)))
          on      <- set(slug, true)
          missing <- set(s"$Prefix-nope", false)
        yield assertTrue(
          // A new location calls its controller unless told otherwise.
          textOf(initial).contains("\"enabled\":true"),
          !off.isError.contains(true),
          textOf(off).contains("\"enabled\":false"),
          // The row, so a write rqlite rejected cannot pass for one that happened.
          stored.find(_.slug == slug).exists(_.unifiIsEnabled == 0),
          textOf(after).contains("\"enabled\":false"),
          textOf(on).contains("\"enabled\":true"),
          missing.isError.contains(true),
          textOf(missing).contains("No existe")
        )
        end for
      // The schema is laid down once by `mcp.test` before forking — see `migratedRqliteUrl` in
      // build.mill.
    ).provideShared(
      RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)) @@
      TestAspect.sequential
end LocationToolsSpec
