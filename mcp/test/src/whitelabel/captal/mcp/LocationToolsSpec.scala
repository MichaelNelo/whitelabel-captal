package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRow, RqliteDataSource}
import zio.*
import zio.test.*

import QuillArrow.->

/** `locations.slug` is UNIQUE and rqlite does enforce it — but it reports the rejection as a field
  * inside an HTTP 200, which the driver turns into zero rows affected rather than an exception.
  * Disabling the Scala pre-check leaves the row correctly unwritten and degrades the message to
  * "the database rejected it silently", so what these tests pin is the diagnosis, not the
  * integrity.
  *
  * Rows are scoped to a slug prefix rather than cleared wholesale, because suites run in parallel
  * against one rqlite instance and other specs keep locations of their own.
  */
object LocationToolsSpec extends ZIOSpecDefault:
  private val Prefix = "loc-spec"

  /** Filtered in Scala rather than SQL: Quill's SQLite dialect has no `startsWith`, and the handful
    * of rows a test writes makes the round trip irrelevant.
    */
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
          // The row count is what the column guarantees; the message is what the Scala check adds.
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
          // All three match the `[a-z0-9-]+` that file provisioning accepted.
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
        // The point of the tool: every other tool refuses a slug it cannot resolve, so this is what
        // turns a fresh database into one the rest of the server can act on.
        val slug = s"$Prefix-usable"
        for
          _      <- clear
          _      <- create(slug, "Usable")
          status <- I18nTools.status.call(json("slug" -> str(slug)))
        yield assertTrue(
          // The location resolves, which is the difference between this and "No location with
          // slug" — the error every other tool gives for a slug that does not exist.
          !status.isError.contains(true),
          !textOf(status).contains("No location"),
          textOf(status).contains(s"\"slug\":\"$slug\""),
          // No locale has a single key yet, which is why create_location tells the operator to add
          // translations before deploying: the portal would render raw key names.
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
          // Nothing configures UniFi at creation, and the listing has to say so rather than let an
          // operator assume the portal can authorize guests there.
          textOf(listed).contains("\"unifiConfigured\":false")
        )
      // The schema is laid down once by `mcp.test` before forking — see `migratedRqliteUrl` in
      // build.mill. A Flyway run per spec raced.
    ).provideShared(
      RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)) @@
      TestAspect.sequential
end LocationToolsSpec
