package whitelabel.captal.cli.migrations

import io.circe.Json

/** Primitive operations applied to a parsed YAML (modeled as `io.circe.Json`). Each op is
  * idempotent — re-applying after success is a no-op — so the migrate command can be safely
  * re-run.
  */
enum YamlOp:
  /** Set the field at `path` to `value` if it's currently missing. No-op when the path is already
    * present (don't clobber operator overrides). Use for adding optional fields with sensible
    * defaults.
    */
  case Add(path: YamlPath, value: Json)

  /** Remove the field at `path` if present. No-op when missing. */
  case Delete(path: YamlPath)

  /** Move the value at `from` to `to`. Preserves value bit-for-bit (no parse/format change). No-op
    * when `from` doesn't exist. Use [[Delete]] + [[Add]] explicitly when the value format also
    * changes — Rename only carries the value through.
    */
  case Rename(from: YamlPath, to: YamlPath)

object YamlOp:
  extension (op: YamlOp)
    /** Human-readable summary for the migrate command's plan output. */
    def describe: String = op match
      case Add(p, v)     => s"add ${p.render} = ${v.noSpaces}"
      case Delete(p)     => s"delete ${p.render}"
      case Rename(f, t)  => s"rename ${f.render} -> ${t.render}"
end YamlOp
