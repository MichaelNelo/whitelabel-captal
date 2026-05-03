package whitelabel.captal.cli.templates

import java.nio.file.{Files, Path}

import scala.io.Source

import zio.*

/** Pure-data template: a relative path and the file content. */
final case class Template(path: String, content: String)

/** Writes templates to disk, skipping files that already exist. */
object TemplateWriter:

  def writeIfAbsent(baseDir: Path, template: Template): Task[Boolean] = ZIO.attempt:
    val target = baseDir.resolve(template.path)
    if Files.exists(target) then
      false
    else
      Files.createDirectories(target.getParent)
      Files.writeString(target, template.content)
      true

  def writeAllIfAbsent(baseDir: Path, templates: List[Template]): Task[Unit] =
    ZIO.foreachDiscard(templates)(writeIfAbsent(baseDir, _))

/** Loads template files from classpath resources with placeholder substitution. */
object Templates:

  /** Load a single template, replacing `{{key}}` placeholders with values. */
  def load(resourcePath: String, vars: Map[String, String] = Map.empty): Template =
    val raw = Source.fromResource(s"templates/$resourcePath")(using scala.io.Codec.UTF8).mkString
    val resolved =
      vars.foldLeft(raw) { case (c, (k, v)) =>
        c.replace(s"{{$k}}", v)
      }
    Template(resourcePath, resolved)

  /** Load a template and override its output path. */
  def loadAs(
      resourcePath: String,
      outputPath: String,
      vars: Map[String, String] = Map.empty): Template = load(resourcePath, vars).copy(path =
    outputPath)

  /** Load a list of known template files with placeholder substitution. */
  def loadAll(files: List[String], vars: Map[String, String] = Map.empty): List[Template] = files
    .map(load(_, vars))
end Templates
