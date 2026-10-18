package whitelabel.captal.mcp.protocol

import scala.io.Source
import scala.util.Using

/** One bundled operator skill, as served over `skill://`.
  *
  * `name` and `description` come from the SKILL.md frontmatter and become the resource's own name
  * and description, so a host can decide whether a skill is worth reading without fetching its
  * body.
  */
final case class Skill(slug: String, name: String, description: String, body: String):
  def uri: String = s"skill://$slug/SKILL.md"

object Skills:
  private val IndexResource = "/skills/index.txt"

  /** Loaded once at startup. The files ship inside the jar, so they cannot change under us and
    * there is nothing to invalidate.
    */
  lazy val all: List[Skill] = index.flatMap(load)

  lazy val bySlug: Map[String, Skill] = all.map(s => s.slug -> s).toMap

  private def index: List[String] = readResource(IndexResource)
    .toList
    .flatMap(_.linesIterator.map(_.trim).filter(_.nonEmpty))

  private def load(slug: String): Option[Skill] = readResource(s"/skills/$slug/SKILL.md").map:
    content =>
      val (frontmatter, body) = splitFrontmatter(content)
      Skill(
        slug = slug,
        // Fall back to the slug rather than dropping the skill: a malformed frontmatter should
        // cost the nice label, not the document.
        name = frontmatter.getOrElse("name", slug),
        description = frontmatter.getOrElse("description", ""),
        body = body
      )

  /** Splits a `---`-delimited YAML frontmatter block off the top of the document.
    *
    * Deliberately not a YAML parser: the only keys read are `name` and `description`, both plain
    * scalars on a single line, and pulling in a parser to read two strings is not worth the
    * dependency.
    */
  private[mcp] def splitFrontmatter(content: String): (Map[String, String], String) =
    val lines = content.linesIterator.toList
    lines match
      case head :: rest if head.trim == "---" =>
        rest.span(_.trim != "---") match
          case (fm, _ :: body) =>
            val pairs = fm.flatMap: line =>
              line.split(":", 2) match
                case Array(k, v) if k.trim.nonEmpty =>
                  Some(k.trim -> v.trim)
                case _ =>
                  None
            (pairs.toMap, body.mkString("\n"))
          // No closing delimiter: treat the whole thing as body rather than silently truncating.
          case _ =>
            (Map.empty, content)
      case _ =>
        (Map.empty, content)

  private def readResource(path: String): Option[String] = Option(
    getClass.getResourceAsStream(path)).flatMap: stream =>
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).toOption
end Skills
