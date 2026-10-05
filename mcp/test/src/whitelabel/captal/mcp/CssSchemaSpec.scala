package whitelabel.captal.mcp

import zio.test.*

/** The CSS variable list, which is derived rather than written down.
  *
  * Derivation is the whole point — a hand-kept list recommends variables the stylesheet dropped,
  * and the operator who themes one sees nothing change and has nothing to blame. These pin that the
  * derivation actually produces something, and that the prose beside it has not outlived its
  * subject.
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
      // The `:root` block only. A variable declared on some other selector is not something a
      // location overrides, and offering it would be an instruction that quietly does nothing.
      test("ignores declarations outside the root block"):
        assertTrue(!CssSchema.parse(sample).flatMap(_.variables).exists(_.name == "--ignored"))
      ,
      // The resource has to be in the jar and the parser has to find something in it. Both fail
      // silently otherwise: an empty list reads as "this portal has nothing to theme".
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
      // Drift in the direction a derived list cannot catch by itself: prose describing a variable
      // that no longer exists. The names come from the stylesheet, so a renamed one simply loses
      // its explanation — and this is what says so.
      test("every documented variable still exists in the stylesheet"):
        val declared = CssSchema.all.map(_.name).toSet
        val orphaned = CssSchema.Purposes.keySet -- declared
        assertTrue(orphaned.isEmpty)
      ,
      // The variables most worth theming are the ones an operator reaches for first. Undocumented
      // is allowed in general and not for these.
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
