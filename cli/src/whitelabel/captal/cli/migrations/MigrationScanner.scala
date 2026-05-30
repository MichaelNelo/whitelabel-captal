package whitelabel.captal.cli.migrations

import java.nio.file.Path

import scala.collection.mutable

import zio.*

/** Resolves "which YAML files in the project still need migrations". Two entry points:
  *
  *   - [[fullScan]]: walks every registered migration's `fileGlob` against the project root and
  *     returns all files with at least one pending Delete/Rename op. Used on the first invocation
  *     post-CLI-update to populate the operator's prompt.
  *   - [[rescanFiles]]: given a list of paths (typically the previously-cached `pendingFiles` from
  *     [[CliState]]), re-evaluate ONLY those files. Used on every subsequent invocation —
  *     sub-millisecond even with dozens of cached paths.
  *
  * Both filter out `Add` ops from the "pending" determination (operator may legitimately want
  * optional fields absent — see plan section G).
  */
object MigrationScanner:

  /** Full project scan: iterate every registered migration's fileGlob, dedupe matching files, and
    * return those with at least one pending Delete/Rename op against any registered migration.
    *
    * Result paths are relative to the project root (cwd) and joined with `/` for cross-platform
    * stability in the state file.
    */
  val fullScan: UIO[List[String]] = ZIO
    .attemptBlocking:
      val root = FileGlob.projectRoot
      // Dedupe: a file matched by multiple fileGlobs is scanned once.
      val candidates = mutable.LinkedHashSet.empty[Path]
      Migrations.all.foreach: m =>
        FileGlob.resolve(m.fileGlob, root).foreach(candidates.add)
      candidates
        .filter(filePending)
        .map(p => root.relativize(p).toString.replace('\\', '/'))
        .toList
    .orElseSucceed(Nil)

  /** Re-scan ONLY the given paths (relative to project root). Returns the subset that still has
    * at least one pending Delete/Rename op against any registered migration.
    *
    * Used on subsequent invocations: the previously-cached list is filtered down, and the result
    * goes back into `CliState.pendingFiles`. When the result is empty, the caller deletes the
    * state file (self-cleanup).
    */
  def rescanFiles(relativePaths: List[String]): UIO[List[String]] = ZIO
    .attemptBlocking:
      val root = FileGlob.projectRoot
      relativePaths.filter: rel =>
        val abs = root.resolve(rel)
        java.nio.file.Files.exists(abs) && filePending(abs)
    .orElseSucceed(Nil)

  /** True iff `file` has at least one pending Delete/Rename op against any registered migration.
    * Add-only migrations are ignored (their ops are silent in warning paths — see plan §G).
    */
  private def filePending(file: Path): Boolean =
    YamlIo.read(file) match
      case Right(json) =>
        Migrations.all.exists: m =>
          MigrationRunner
            .pendingOps(json, m.ops)
            .exists:
              case _: YamlOp.Add => false
              case _             => true
      case Left(_) => false  // unparseable file → not "pending"; migrate can't help either
end MigrationScanner
