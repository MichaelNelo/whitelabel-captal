package whitelabel.captal.cli.migrations

import io.circe.{Json, JsonObject}

/** Pure interpreters for [[YamlOp]] over `io.circe.Json` trees. Both `applyOps` (mutating) and
  * `pendingOps` (read-only) reuse the same navigation helpers.
  */
object MigrationRunner:

  /** Apply each op in order. Returns the resulting JSON plus a list of human-readable summaries
    * of the ops that actually changed something (used by the migrate command to print a plan).
    */
  def applyOps(json: Json, ops: List[YamlOp]): (Json, List[String]) =
    ops.foldLeft((json, List.empty[String])) { case ((acc, changes), op) =>
      val (next, changed) = applyOne(acc, op)
      (next, if changed then changes :+ op.describe else changes)
    }

  /** The subset of ops that WOULD change the JSON. Used to detect pending migrations cheaply
    * (no write happens).
    */
  def pendingOps(json: Json, ops: List[YamlOp]): List[YamlOp] = ops.filter(op => isPending(json, op))

  private def applyOne(json: Json, op: YamlOp): (Json, Boolean) = op match
    case YamlOp.Add(path, value) =>
      if getAt(json, path).isDefined then (json, false)
      else (setAt(json, path, value), true)
    case YamlOp.Delete(path) =>
      if getAt(json, path).isEmpty then (json, false)
      else (removeAt(json, path), true)
    case YamlOp.Rename(from, to) =>
      getAt(json, from) match
        case None    => (json, false)
        case Some(v) =>
          val cleared = removeAt(json, from)
          (setAt(cleared, to, v), true)

  private def isPending(json: Json, op: YamlOp): Boolean = op match
    case YamlOp.Add(path, _)    => getAt(json, path).isEmpty
    case YamlOp.Delete(path)    => getAt(json, path).isDefined
    case YamlOp.Rename(from, _) => getAt(json, from).isDefined

  // ─── JSON tree navigation ───────────────────────────────────────────────────

  private def getAt(json: Json, path: YamlPath): Option[Json] =
    path.parts.foldLeft(Option(json)):
      case (Some(j), key) => j.asObject.flatMap(_(key))
      case (None, _)      => None

  /** Set `path` to `value`, creating intermediate objects as needed. Overwrites existing leaves. */
  private def setAt(json: Json, path: YamlPath, value: Json): Json =
    val parts = path.parts
    def loop(current: Json, idx: Int): Json =
      if idx == parts.length - 1 then
        // Leaf: set the final key.
        current.asObject match
          case Some(obj) => Json.fromJsonObject(obj.add(parts(idx), value))
          case None      => Json.fromJsonObject(JsonObject(parts(idx) -> value))
      else
        val key = parts(idx)
        val obj = current.asObject.getOrElse(JsonObject.empty)
        val child = obj(key).getOrElse(Json.obj())
        val updated = loop(child, idx + 1)
        Json.fromJsonObject(obj.add(key, updated))
    loop(json, 0)

  /** Remove `path`. Leaves empty parent objects in place (operator can clean those up manually). */
  private def removeAt(json: Json, path: YamlPath): Json =
    val parts = path.parts
    def loop(current: Json, idx: Int): Json =
      val key = parts(idx)
      current.asObject match
        case None      => current
        case Some(obj) =>
          if idx == parts.length - 1 then Json.fromJsonObject(obj.remove(key))
          else
            obj(key) match
              case None        => current
              case Some(child) =>
                Json.fromJsonObject(obj.add(key, loop(child, idx + 1)))
    loop(json, 0)
end MigrationRunner
