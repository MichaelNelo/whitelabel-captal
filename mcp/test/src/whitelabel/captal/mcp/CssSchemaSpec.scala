package whitelabel.captal.mcp

import whitelabel.captal.mcp.protocol.CssSchema
import zio.test.*

/** The CSS variable list, derived from the bundled stylesheet: that the derivation produces
  * something, and that the prose beside it still matches a declared variable.
  */
object CssSchemaSpec extends ZIOSpecDefault:

  private val sample =
    """:root {
      |  /* ═══════════════════
      |     THEME COLORS
      |     ═══════════════════ */
      |  --color-primary: #6366f1;
      |  --color-error: #ef4444;
      |
      |  /* ═══════════════════
      |     SPACING
      |     ═══════════════════ */
      |  --space-md: 16px;
      |}
      |
      |.not-root {
      |  --ignored: 1px;
      |}
      |""".stripMargin

  def spec =
    suite("CssSchema")(
      test("reads the variables under their own headings"):
        val groups = CssSchema.parse(sample)
        assertTrue(
          groups.map(_.group) == List("THEME COLORS", "SPACING"),
          groups.head.variables.map(_.name) == List("--color-primary", "--color-error"),
          groups.head.variables.head.default == "#6366f1",
          groups.last.variables.map(_.name) == List("--space-md")
        )
      ,
      // The `:root` block only; a variable on another selector is not something a location
      // overrides.
      test("ignores declarations outside the root block"):
        assertTrue(!CssSchema.parse(sample).flatMap(_.variables).exists(_.name == "--ignored"))
      ,
      // The resource has to be in the jar and the parser has to find something in it; both fail
      // silently otherwise.
      test("the bundled stylesheet yields the real variables"):
        val all = CssSchema.all
        assertTrue(
          all.size > 60,
          all.forall(_.name.startsWith("--")),
          all.forall(_.default.nonEmpty),
          all.map(_.name).distinct.size == all.size,
          all.exists(_.name == "--color-primary"),
          CssSchema.groups.map(_.group).contains("TYPOGRAPHY")
        )
      ,
      test("every documented variable still exists in the stylesheet"):
        val declared = CssSchema.all.map(_.name).toSet
        val orphaned = CssSchema.Purposes.keySet -- declared
        assertTrue(orphaned.isEmpty)
      ,
      // Undocumented is allowed in general, not for these.
      test("the variables a rebrand starts from are documented"):
        val documented = CssSchema.all.filter(_.purpose.nonEmpty).map(_.name).toSet
        assertTrue(
          Set(
            "--color-primary",
            "--bg-gradient-start",
            "--bg-gradient-end",
            "--font-family",
            "--text-primary").forall(documented.contains))
    )
end CssSchemaSpec
