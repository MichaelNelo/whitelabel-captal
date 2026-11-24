package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import io.getquill.*
import whitelabel.captal.core.application.commands.RegisterAccessPointCommand
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{
  ControllerChoice,
  ControllerId,
  Secret,
  SettingsChange,
  Slug
}
import whitelabel.captal.mcp.aws.DeployTargets
import whitelabel.captal.mcp.tools.{Commands, I18nTools, LocationFlows, LocationTools, UnifiTools}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AccessPointRow,
  ActiveAccessPointRow,
  LocationAccessPointRow,
  LocationRow,
  Operator,
  UnifiControllerRow
}
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

  /** The access point and the controller this spec's locations are configured with: the system's,
    * so its own, apart from the other specs'.
    */
  private val SpecAp = "02:00:00:5c:00:01"
  private val SpecController = ControllerId(s"$Prefix-controller")

  private def clear: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    rows.flatMap: owned =>
      ZIO.foreachDiscard(owned): location =>
        quill.run(query[ActiveAccessPointRow].filter(_.locationId == lift(location.id)).delete) *>
          quill.run(
            query[LocationAccessPointRow].filter(_.locationId == lift(location.id)).delete) *>
          quill.run(query[LocationRow].filter(_.slug == lift(location.slug)).delete)
    *> quill.run(query[AccessPointRow].filter(_.mac == lift(SpecAp)).delete) *>
      quill.run(query[UnifiControllerRow].filter(_.id == lift(SpecController.asString)).delete).unit

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
    .callWithContext(json("slug" -> str(slug), "name" -> str(name)), OperatorContext.Default)

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
          mcp      <- create("mcp", "Backoffice")
          written  <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            quill
              .run(query[LocationRow])
              .map(_.filter(row => Set("ui", "bundle", "releases", "mcp").contains(row.slug)))
        yield assertTrue(
          ui.isError.contains(true),
          bundle.isError.contains(true),
          releases.isError.contains(true),
          mcp.isError.contains(true),
          textOf(ui).contains("reserved"),
          written.isEmpty
        )
      ,
      test("a slug has no length of its own, and its target group's name still fits AWS's 32"):
        val long = s"$Prefix-" + "x" * 40
        val short = s"$Prefix-fits"
        for
          _      <- clear
          result <- create(long, "Long")
          stored <- rows
        yield assertTrue(
          !result.isError.contains(true),
          stored.map(_.slug) == List(long),
          DeployTargets.targetGroupName(long).length <= 32,
          // One that fits keeps the name it always had.
          DeployTargets.targetGroupName(short) == s"captal-$short"
        )
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
      test("UniFi calls are turned off and back on per access point, and get_unifi_config says so"):
        val slug = s"$Prefix-unifi"
        val ap = SpecAp
        def set(target: String, enabled: Boolean) = UnifiTools
          .updateAccessPoint()
          .callWithContext(
            json(
              "slug"         -> str(target),
              "apMac"        -> str(ap),
              "unifiEnabled" -> zio.json.ast.Json.Bool(enabled)),
            OperatorContext.Default)
        for
          _    <- clear
          _    <- create(slug, "UniFi")
          flow <- ZIO.service[LocationFlows.RegisterAccessPoint]
          _    <- Commands
            .run(
              Operator("spec-operator", None),
              RegisterAccessPointCommand(
                Slug.unsafe(slug),
                Mac.parse(ap).get,
                Some(
                  ControllerChoice.New(
                    SpecController,
                    s"unifi.$Prefix.test",
                    None,
                    "site",
                    Secret.parse("token").get)),
                SettingsChange(),
                java.time.Instant.now
              )
            )(flow)()
            .mapError(IllegalStateException(_))
          initial <- UnifiTools.status().call(json("slug" -> str(slug)))
          off     <- set(slug, false)
          stored  <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            quill.run(query[LocationAccessPointRow].filter(_.accessPointId == lift(ap)))
          on      <- set(slug, true)
          missing <- set(s"$Prefix-nope", false)
        yield assertTrue(
          // A new access point calls its controller unless told otherwise.
          textOf(initial).contains("\"unifiEnabled\":true"),
          !off.isError.contains(true),
          textOf(off).contains("\"unifiEnabled\":false"),
          // The row, so a write rqlite rejected cannot pass for one that happened.
          stored.exists(_.unifiIsEnabled == 0),
          textOf(on).contains("\"unifiEnabled\":true"),
          missing.isError.contains(true),
          textOf(missing).contains("No location has the slug")
        )
        end for
      // The schema is laid down once by `mcp.test` before forking — see `migratedRqliteUrl` in
      // build.mill.
    ).provideLayerShared(ToolsEnvironment.withFlows) @@ TestAspect.sequential
end LocationToolsSpec
