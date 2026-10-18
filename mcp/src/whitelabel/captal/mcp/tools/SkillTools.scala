package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.mcp.protocol.{McpSchema, Skills, ToolSchema}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** The skills, reachable by the model.
  *
  * They are served as `skill://` resources and still are — that is how the operator attaches one,
  * and how a host that exposes resources to its model offers them. But Claude Desktop does not:
  * resources go to the operator's attachment menu and never to the model. So the server's
  * instructions told every conversation to "read the relevant `skill://` resource before acting",
  * and in the host the operators actually use, the model could not. Nineteen hundred lines of
  * procedure sat behind a door with no handle on its side.
  *
  * One parameterised tool rather than fourteen. A collection does not need a tool each, and
  * fourteen schemas would be paid in every conversation to say what one says.
  */
object SkillTools:

  final case class ReadRequest(
      @description(
        "Which skill, by slug — 'add-video'. This tool's own description lists every one with what it covers.")
      name: String)

  final case class ReadResponse(
      @description("The slug, as asked for.")
      slug: String,
      @description("Its title, from the document's frontmatter.")
      name: String,
      @description(
        "The procedure, as Markdown. Follow it; it encodes constraints that are not visible from the data, and the steps are ordered because the order matters.")
      body: String)

  given Schema[ReadRequest] = DeriveSchema.gen
  given Schema[ReadResponse] = DeriveSchema.gen

  /** The catalogue, rendered into the description.
    *
    * It cannot be an enum on the input: the skills are read from the jar at startup, so there is
    * nothing for a compile-time schema to enumerate. The description is the next best place and
    * arguably the right one — it is what a model reads when deciding whether to call at all.
    *
    * This replaces the catalogue the server's instructions carried. Not a saving: `tools/list` is
    * loaded in every conversation too, so the same text is paid either way. What changes is that it
    * now sits against the thing that can act on it, and says what each skill covers rather than
    * only that it exists.
    */
  private def catalogue: String = Skills
    .all
    .map: skill =>
      val what =
        if skill.description.isEmpty then
          ""
        else
          s" — ${skill.description}"
      s"- ${skill.slug}$what"
    .mkString("\n")

  val read: McpToolHandlerR[Any] = McpTool("read_skill")
    .description(
      s"""Read an operator skill: a procedure to follow before acting in its area, not background reading. Call this first when a request falls under one of them — they encode constraints the data does not show, and the common mistakes they prevent reach guests in production.
         |
         |$catalogue""".stripMargin)
    // The one most worth declaring. A procedure the model is told to read before acting should not
    // cost an approval to read, or it will be skipped exactly when it matters.
    .readOnly
    .handle: (in: ReadRequest) =>
      val slug = in.name.trim.stripPrefix("skill://").stripSuffix("/SKILL.md")
      ZIO
        .fromOption(Skills.bySlug.get(slug))
        .orElseFail(
          s"No skill called '$slug'. Available: ${Skills.all.map(_.slug).mkString(", ")}.")
        .map(skill => ReadResponse(slug = skill.slug, name = skill.name, body = skill.body))

  val all: List[McpToolHandlerR[Any]] = List(read)
end SkillTools
