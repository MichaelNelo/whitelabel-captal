package whitelabel.captal.mcp

import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import zio.test.*

/** Asserts against the resources as a host would see them — definition plus body — rather than
  * against the loader internals, so a packaging mistake (skills missing from the jar, a wrong
  * resource path) fails here rather than at runtime in front of an operator.
  */
object CaptalMcpSpec extends ZIOSpecDefault:
  private val ExpectedSkillCount = 14

  def spec =
    suite("CaptalMcp")(
      suite("skills")(
        test("every bundled skill is loaded from the packaged resources"):
          assertTrue(Skills.all.size == ExpectedSkillCount)
        ,
        test("frontmatter becomes the resource name and description"):
          val survey = Skills.bySlug.get("add-survey")
          assertTrue(
            survey.isDefined,
            survey.get.name == "add-survey",
            survey.get.description.contains("identification surveys"),
            // The frontmatter must not leak into the served body.
            !survey.get.body.startsWith("---"),
            survey.get.body.nonEmpty
          )
        ,
        test("no skill loses its body or its description"):
          val empty = Skills.all.filter(s => s.body.isBlank || s.description.isBlank)
          assertTrue(empty.isEmpty)
        ,
        test("uris follow the skill:// convention and are unique"):
          val uris = Skills.all.map(_.uri)
          assertTrue(
            uris.forall(u => u.startsWith("skill://") && u.endsWith("/SKILL.md")),
            uris.distinct.size == uris.size)
      ),
      suite("frontmatter parsing")(
        test("a document without frontmatter is returned untouched"):
          val (fm, body) = Skills.splitFrontmatter("# Title\n\ntext")
          assertTrue(fm.isEmpty, body == "# Title\n\ntext")
        ,
        test("an unterminated frontmatter block is treated as body, not truncated"):
          val raw = "---\nname: x\nstill going"
          val (fm, body) = Skills.splitFrontmatter(raw)
          assertTrue(fm.isEmpty, body == raw)
        ,
        test("values containing colons survive"):
          val (fm, _) = Skills.splitFrontmatter("---\ndescription: Use this: carefully\n---\nbody")
          assertTrue(fm.get("description").contains("Use this: carefully"))
      ),
      suite("resources")(
        test("one resource per skill plus the derived i18n key list"):
          assertTrue(CaptalMcp.resources.size == ExpectedSkillCount + 1)
        ,
        test("required-keys serves the derived keys, each with its description"):
          val handler =
            CaptalMcp.resources.find(_.definition.uri == CaptalMcp.RequiredI18nKeysUri).get
          for contents <- handler.read(CaptalMcp.RequiredI18nKeysUri)
          yield
            val lines = contents.head.text.get.linesIterator.toList
            val served = lines.map(_.takeWhile(_ != ':'))
            assertTrue(
              served == I18nSchema.keys[I18n],
              served.size == 39,
              served.contains("welcome.steps.step1"),
              // Every line must carry a description, not just the bare key.
              lines.forall(l => l.split(": ", 2).lift(1).exists(_.trim.nonEmpty))
            )
        ,
        test("a skill resource serves its own body"):
          val handler = CaptalMcp.resources.find(_.definition.uri.contains("add-survey")).get
          for contents <- handler.read(handler.definition.uri)
          yield assertTrue(
            contents.size == 1,
            contents.head.mimeType.contains("text/markdown"),
            contents.head.text.exists(_.nonEmpty))
      ),
      suite("instructions")(
        test("index every skill without inlining any body"):
          val bodies = Skills.all.map(_.body)
          assertTrue(
            Skills.all.forall(s => CaptalMcp.instructions.contains(s.uri)),
            CaptalMcp.instructions.contains(CaptalMcp.RequiredI18nKeysUri),
            // The whole point of an index: bodies stay behind the resources.
            bodies.forall(b => !CaptalMcp.instructions.contains(b)),
            CaptalMcp.instructions.length < 2000
          )),
      suite("tools")(
        test("the three i18n tools are registered with stable names"):
          assertTrue(
            I18nTools.all.map(_.name.value).sorted ==
              List("get_i18n_status", "remove_translation", "set_translations"))
        ,
        test("every tool carries a description and an object input schema"):
          val defs = I18nTools.all.map(_.definition)
          assertTrue(
            defs.forall(_.description.exists(_.length > 20)),
            defs.forall(_.inputSchema.get("type").contains(zio.json.ast.Json.Str("object"))))
        ,
        test("set_translations declares the fields an operator must supply"):
          val props = I18nTools
            .setTranslations
            .definition
            .inputSchema
            .get("properties")
            .collect { case o: zio.json.ast.Json.Obj =>
              o
            }
          assertTrue(
            props.isDefined,
            List("slug", "locale", "entries").forall(f => props.get.get(f).isDefined))
        ,
        test("the write tool points the reader at the derived key list"):
          // Without this the operator has no way to learn which keys are legal, and the tool would
          // reject their input with nowhere to go next.
          assertTrue(
            I18nTools
              .setTranslations
              .definition
              .description
              .exists(_.contains("i18n://required-keys")))
      )
    )
end CaptalMcpSpec
