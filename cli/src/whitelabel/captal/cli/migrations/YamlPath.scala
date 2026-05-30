package whitelabel.captal.cli.migrations

import scala.compiletime.constValue
import scala.compiletime.ops.string.Matches

/** Dot-separated path to a nested key inside a YAML document. Wraps a `Vector[String]` of segments.
  *
  *   - For literal paths (e.g. `YamlPath("unifi.siteId")`), validation runs at **compile time** via
  *     `compiletime.ops.string.Matches` regex on the singleton type. Malformed literals fail to
  *     compile with the `error(...)` message.
  *   - For dynamic strings, use [[parse]] which returns Either[String, YamlPath] at runtime.
  */
opaque type YamlPath = Vector[String]

object YamlPath:
  /** Java regex matching dot-separated non-empty segments. Anchored at both ends. Used at the type
    * level for compile-time validation, and at the value level for runtime parsing.
    */
  private inline val Pattern = "^[^.]+(\\.[^.]+)*$"

  /** Compile-time constructor — fails compilation if `s` is a literal that doesn't match Pattern.
    *
    * Examples:
    * {{{
    *   YamlPath("unifi.siteId")   // ✓ compiles
    *   YamlPath("ap_mac")         // ✓ compiles (single segment, no dots needed)
    *   YamlPath("unifi..siteId")  // ✗ compile error (empty segment)
    *   YamlPath("")               // ✗ compile error
    *   YamlPath(".unifi")         // ✗ compile error (leading dot)
    * }}}
    *
    * The `S <: Singleton & String` bound forces type inference to keep the literal type so
    * `Matches[S, Pattern.type]` reduces to `true`/`false` at the type level.
    */
  inline def apply[S <: Singleton & String](s: S): YamlPath =
    inline if constValue[Matches[S, Pattern.type]] then Vector.from(s.split('.'))
    else
      scala
        .compiletime
        .error(
          "Invalid YamlPath: '" + constValue[S] +
            "' (must be dot-separated non-empty segments)")

  /** Runtime parse for dynamic strings (e.g. read from CLI args, config files). For hardcoded
    * literals prefer [[apply]] so the validation runs at compile time.
    */
  def parse(raw: String): Either[String, YamlPath] =
    if raw.matches(Pattern) then Right(Vector.from(raw.split('.')))
    else Left(s"Invalid YamlPath: '$raw' (must be dot-separated non-empty segments)")

  /** Build from a non-empty list of segments. No validation on segments themselves (the caller
    * promises they're well-formed). Useful for programmatic construction (e.g. when navigating a
    * JSON tree).
    */
  def of(segments: String*): YamlPath = Vector.from(segments)

  extension (p: YamlPath)
    def parts: Vector[String]    = p
    def head: String             = p.head
    def tail: YamlPath           = p.drop(1)
    def isRoot: Boolean          = p.size == 1
    def render: String           = p.mkString(".")
    def parent: Option[YamlPath] = if p.size <= 1 then None else Some(p.dropRight(1))
    def leaf: String             = p.last
end YamlPath
