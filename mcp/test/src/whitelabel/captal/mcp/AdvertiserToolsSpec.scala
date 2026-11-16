package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import io.getquill.*
import whitelabel.captal.mcp.tools.AdvertiserTools
import whitelabel.captal.platform.AdvertiserRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.test.*

/** AdvertiserTools exercised against a real rqlite instance. Identity rules are enforced in Scala,
  * not by table constraints.
  */
object AdvertiserToolsSpec extends ZIOSpecDefault:

  private def clear: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    quill.run(query[AdvertiserRow].delete).unit

  private def json(fields: (String, zio.json.ast.Json)*) = Some(
    zio.json.ast.Json.Obj(Chunk.fromIterable(fields)))

  private def str(s: String) = zio.json.ast.Json.Str(s)
  private def num(n: Int) = zio.json.ast.Json.Num(n)

  private def textOf(result: CallToolResult): String =
    result
      .content
      .collect { case ToolContent.Text(t, _) =>
        t
      }
      .mkString

  private def create(name: String, priority: Int) = AdvertiserTools
    .create
    .callWithContext(
      json("name" -> str(name), "priority" -> num(priority)),
      OperatorContext.Default)

  private def update(fields: (String, zio.json.ast.Json)*) = AdvertiserTools
    .update
    .callWithContext(json(fields*), OperatorContext.Default)

  private def rows
      : ZIO[QuillSqlite, Throwable, List[AdvertiserRow]] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    quill.run(query[AdvertiserRow])

  def spec =
    suite("AdvertiserTools against rqlite")(
      test("creating twice with the same name is refused, and nothing is written the second time"):
        for
          _      <- clear
          first  <- create("Acme", 10)
          second <- create("Acme", 5)
          stored <- rows
        yield assertTrue(
          !first.isError.contains(true),
          second.isError.contains(true),
          textOf(second).contains("already exists"),
          stored.size == 1,
          stored.head.priority == 10)
      ,
      test("a negative priority is refused before it reaches the database"):
        for
          _      <- clear
          result <- create("Bad", -1)
          stored <- rows
        yield assertTrue(
          result.isError.contains(true),
          textOf(result).contains("zero or greater"),
          stored.isEmpty)
      ,
      test("a blank name is refused"):
        for
          _      <- clear
          result <- create("   ", 1)
          stored <- rows
        yield assertTrue(result.isError.contains(true), stored.isEmpty)
      ,
      test("update leaves omitted fields alone"):
        for
          _       <- clear
          created <- create("Acme", 10)
          id = textOf(created).split("\"id\":\"").lift(1).map(_.takeWhile(_ != '"')).getOrElse("")
          updated <- update("id" -> str(id), "priority" -> num(3))
          stored  <- rows
        yield assertTrue(
          !updated.isError.contains(true),
          stored.head.name == "Acme",
          stored.head.priority == 3)
      ,
      test("update refuses to rename onto another advertiser's name"):
        for
          _       <- clear
          _       <- create("Acme", 10)
          created <- create("Globex", 5)
          id = textOf(created).split("\"id\":\"").lift(1).map(_.takeWhile(_ != '"')).getOrElse("")
          renamed <- update("id" -> str(id), "name" -> str("Acme"))
          stored  <- rows
        yield assertTrue(
          renamed.isError.contains(true),
          stored.count(_.name == "Acme") == 1,
          stored.exists(_.name == "Globex"))
      ,
      test("an unknown id is reported rather than silently doing nothing"):
        for
          _      <- clear
          result <- update("id" -> str("nope"), "priority" -> num(1))
        yield assertTrue(result.isError.contains(true), textOf(result).contains("No advertiser"))
      ,
      test("retiring flips is_active and reports how many videos it takes down"):
        for
          _       <- clear
          created <- create("Acme", 10)
          id = textOf(created).split("\"id\":\"").lift(1).map(_.takeWhile(_ != '"')).getOrElse("")
          retired <- AdvertiserTools
            .setActive
            .callWithContext(
              json("id" -> str(id), "active" -> zio.json.ast.Json.Bool(false)),
              OperatorContext.Default)
          stored <- rows
          listed <- AdvertiserTools.list.call(json())
        yield assertTrue(
          !retired.isError.contains(true),
          stored.head.isActive == 0,
          // Hidden from the default listing once retired.
          !textOf(listed).contains(id))
      // No migration layer: `mcp.test` lays the schema down once before forking — see
      // `migratedRqliteUrl` in build.mill.
    ).provideLayerShared(ToolsEnvironment.withFlows) @@ TestAspect.sequential
end AdvertiserToolsSpec
