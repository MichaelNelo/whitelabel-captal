package whitelabel.captal.cli.templates

import java.nio.file.{Files, Path}

import zio.*

/** Pure-data template: a relative path and the file content. */
final case class Template(path: String, content: String)

/** Writes templates to disk, skipping files that already exist. */
object TemplateWriter:

  /** Write a single template under `baseDir`. Returns true if written, false if already existed. */
  def writeIfAbsent(baseDir: Path, template: Template): Task[Boolean] =
    ZIO.attempt:
      val target = baseDir.resolve(template.path)
      if Files.exists(target) then false
      else
        Files.createDirectories(target.getParent)
        Files.writeString(target, template.content)
        true

  /** Write all templates under `baseDir`, skipping existing files. */
  def writeAllIfAbsent(baseDir: Path, templates: List[Template]): Task[Unit] =
    ZIO.foreachDiscard(templates)(writeIfAbsent(baseDir, _))

/** A minimal DSL for composing YAML content as strings. */
object TemplateDsl:

  extension (key: String)
    def :=(value: String): String  = s"""$key: "$value""""
    def :=(value: Int): String     = s"$key: $value"
    def :=(value: Boolean): String = s"$key: $value"
    def :=(kvs: Map[String, String]): String =
      val entries = kvs.toList.sortBy(_._1).map((k, v) => s"""  $k: "$v"""")
      s"$key:\n${entries.mkString("\n")}"

  /** Join entries with newlines and append a trailing newline. */
  def lines(entries: String*): String = entries.mkString("\n") + "\n"

  /** `section("key", items*)` produces `key:\n  item1\n  item2` (items indented by 2). */
  def section(key: String, items: String*): String =
    val body = items.map(indentBy(2)).mkString("\n")
    s"$key:\n$body"

  /** YAML list item: first line gets `- `, continuation lines get `  `. */
  def item(entries: String*): String =
    val joined = entries.mkString("\n")
    val ls     = joined.linesIterator.toList
    ls.zipWithIndex.map:
      case (line, 0) => s"- $line"
      case (line, _) => s"  $line"
    .mkString("\n")

  /** Prefix every line with `# `. */
  def commented(content: String): String =
    content.linesIterator.map("# " + _).mkString("\n")

  /** Indent every line by `n` spaces. */
  def indentBy(n: Int)(s: String): String =
    val prefix = " " * n
    s.linesIterator.map(prefix + _).mkString("\n")
