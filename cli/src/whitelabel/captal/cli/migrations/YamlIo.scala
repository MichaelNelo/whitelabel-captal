package whitelabel.captal.cli.migrations

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import io.circe.Json
import io.circe.yaml.parser as yamlParser
import io.circe.yaml.{Printer as YamlPrinter}

/** Round-trip read/write for YAML files via circe-yaml.
  *
  * Caveats:
  *   - Comments in the source YAML are LOST in the round-trip (circe → SnakeYAML emit doesn't
  *     preserve them). [[hasComments]] lets the migrate command warn the operator before writing.
  *   - Quote style and key order can also shift. We use SnakeYAML defaults (2-space indent, double
  *     quotes when needed). Diffs across migrate runs are stable but not byte-for-byte identical
  *     to operator-formatted files.
  */
object YamlIo:
  private val printer: YamlPrinter = YamlPrinter.spaces2

  def read(path: Path): Either[Throwable, Json] =
    try
      val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      yamlParser.parse(content).left.map(identity)
    catch case t: Throwable => Left(t)

  def write(path: Path, json: Json): Either[Throwable, Unit] =
    try
      val yaml = printer.pretty(json)
      Files.write(path, yaml.getBytes(StandardCharsets.UTF_8))
      Right(())
    catch case t: Throwable => Left(t)

  /** Heuristic: any non-blank line starting with `#` (after whitespace) counts as a comment.
    * False positives on `#` inside strings are rare in captal YAMLs (no hash symbols in values).
    */
  def hasComments(path: Path): Boolean =
    try
      val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      content.linesIterator.exists(_.trim.startsWith("#"))
    catch case _: Throwable => false
end YamlIo
