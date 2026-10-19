package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.mcp.protocol.{McpSchema, Skills, ToolSchema}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** The skills, reachable by the model. One parameterised tool rather than fourteen. */
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
    * nothing for a compile-time schema to enumerate. The description is the next best place.
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
