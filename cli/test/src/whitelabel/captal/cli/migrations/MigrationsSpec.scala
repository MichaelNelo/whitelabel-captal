package whitelabel.captal.cli.migrations

import io.circe.Json
import io.circe.yaml.parser as yamlParser
import scala.compiletime.testing.{typeCheckErrors, typeChecks}
import utest.*

/** Tests for the schema-migration DSL. The most interesting part is [[yamlPath]], which uses
  * `scala.compiletime.testing` to assert that malformed literals **fail compilation** — the
  * runtime invariants alone wouldn't catch a regression that downgraded compile-time validation
  * to runtime validation.
  */
object MigrationsSpec extends TestSuite:

  val tests = Tests:

    test("YamlPath compile-time validation") - {
      // Positive cases — these compile, hence the test runs at all.
      val ok1: YamlPath = YamlPath("unifi.siteId")
      val ok2: YamlPath = YamlPath("ap_mac")
      val ok3: YamlPath = YamlPath("a.b.c.d.e")
      assert(ok1.render == "unifi.siteId")
      assert(ok2.render == "ap_mac")
      assert(ok3.parts.size == 5)

      // Negative cases — `typeChecks` returns false for source strings that DON'T compile.
      // If we ever regress and these start compiling, the assertions flip the test red.
      assert(!typeChecks("""YamlPath("")"""))            // empty
      assert(!typeChecks("""YamlPath("a..b")"""))        // empty segment
      assert(!typeChecks("""YamlPath(".unifi")"""))      // leading dot
      assert(!typeChecks("""YamlPath("unifi.")"""))      // trailing dot

      // Sanity: the error message mentions YamlPath. Useful as a smoke test for the message
      // surfaced to operators when they typo a hardcoded path in Migrations.all.
      val errors = typeCheckErrors("""YamlPath("a..b")""")
      assert(errors.nonEmpty)
      assert(errors.exists(_.message.contains("Invalid YamlPath")))
    }

    test("YamlPath runtime parse") - {
      assert(YamlPath.parse("unifi.siteId").isRight)
      assert(YamlPath.parse("").isLeft)
      assert(YamlPath.parse("a..b").isLeft)
      assert(YamlPath.parse("a.b.c").map(_.parts.size) == Right(3))
    }

    test("SemVer ordering — numeric not lexicographic") - {
      val ord = summon[Ordering[SemVer]]
      assert(ord.lt(SemVer(2, 0, 0), SemVer(2, 0, 1)))
      assert(ord.lt(SemVer(2, 0, 1), SemVer(2, 1, 0)))
      assert(ord.lt(SemVer(2, 1, 0), SemVer(10, 0, 0)))   // would fail if compared as strings
      assert(SemVer.parseOrZero("garbage") == SemVer.zero)
      assert(SemVer.parse("2.0.1") == Right(SemVer(2, 0, 1)))
    }

    test("Migrations.pendingSince — binary search boundary") - {
      val before2 = Migrations.pendingSince(SemVer(2, 0, 0), SemVer(2, 1, 0))
      assert(before2.size == 1)
      assert(before2.head.version == SemVer(2, 1, 0))

      val fromZero = Migrations.pendingSince(SemVer.zero, SemVer(2, 1, 0))
      assert(fromZero.size == 2)

      val sameVersion = Migrations.pendingSince(SemVer(2, 1, 0), SemVer(2, 1, 0))
      assert(sameVersion.isEmpty)

      // Operator downgraded CLI → no migrations show.
      val downgrade = Migrations.pendingSince(SemVer(2, 1, 0), SemVer(2, 0, 0))
      assert(downgrade.isEmpty)
    }

    test("MigrationRunner.applyOps — rename + delete + add") - {
      val src = """
        |name: Test
        |ap_mac: AA:BB
        |unifi:
        |  host: 192.168.1.1
        |  site: default
        |  unifiOs: true
        |""".stripMargin

      val json = yamlParser.parse(src).toOption.get

      val ops = List(
        YamlOp.Rename(YamlPath("unifi.site"), YamlPath("unifi.siteId")),
        YamlOp.Delete(YamlPath("unifi.unifiOs")),
        YamlOp.Rename(YamlPath("ap_mac"), YamlPath("unifi.apMac")),
        YamlOp.Add(YamlPath("unifi.port"), Json.fromInt(443))
      )

      val (out, changes) = MigrationRunner.applyOps(json, ops)
      assert(changes.size == 4)

      val unifi = out.hcursor.downField("unifi")
      assert(unifi.get[String]("siteId") == Right("default"))
      assert(unifi.get[String]("apMac") == Right("AA:BB"))
      assert(unifi.get[Int]("port") == Right(443))
      // Deleted + renamed-from keys are gone
      assert(unifi.downField("unifiOs").focus.isEmpty)
      assert(unifi.downField("site").focus.isEmpty)
      assert(out.hcursor.downField("ap_mac").focus.isEmpty)
    }

    test("MigrationRunner.applyOps — idempotent on already-migrated YAML") - {
      val migrated = """
        |name: Test
        |unifi:
        |  host: 192.168.1.1
        |  siteId: default
        |  apMac: AA:BB
        |""".stripMargin

      val json = yamlParser.parse(migrated).toOption.get
      val ops = List(
        YamlOp.Rename(YamlPath("unifi.site"), YamlPath("unifi.siteId")),
        YamlOp.Delete(YamlPath("unifi.unifiOs")),
        YamlOp.Rename(YamlPath("ap_mac"), YamlPath("unifi.apMac"))
      )
      val (out, changes) = MigrationRunner.applyOps(json, ops)
      assert(changes.isEmpty)
      assert(out == json)
    }

    test("MigrationRunner.pendingOps — Add is pending only when path missing") - {
      val json = yamlParser
        .parse("""
          |unifi:
          |  host: x
          |""".stripMargin)
        .toOption
        .get

      val ops = List(
        YamlOp.Add(YamlPath("unifi.port"), Json.fromInt(443)),         // missing → pending
        YamlOp.Add(YamlPath("unifi.host"), Json.fromString("y")),      // present → not pending
        YamlOp.Delete(YamlPath("unifi.nope")),                         // missing → not pending
        YamlOp.Rename(YamlPath("unifi.host"), YamlPath("unifi.target")) // present → pending
      )

      val pending = MigrationRunner.pendingOps(json, ops)
      assert(pending.size == 2)
      assert(pending.exists {
        case YamlOp.Add(p, _) => p.render == "unifi.port"
        case _                => false
      })
      assert(pending.exists {
        case YamlOp.Rename(f, _) => f.render == "unifi.host"
        case _                   => false
      })
    }
end MigrationsSpec
