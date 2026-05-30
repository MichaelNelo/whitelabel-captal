package whitelabel.captal.cli.migrations

import java.nio.file.{FileSystems, Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Glob resolver for migration fileGlob patterns. Uses `java.nio.file.PathMatcher` with the
  * standard `glob:` syntax. Example: `locations/&#42;/location.yaml` matches
  * `locations/acme/location.yaml` but not `locations/acme/subdir/location.yaml`.
  */
object FileGlob:
  /** Resolve `pattern` against `root` (typically cwd). Returns all matching paths. Walks the file
    * tree once; for typical captal projects (<100 files) this is sub-millisecond.
    */
  def resolve(pattern: String, root: Path): List[Path] =
    if !Files.exists(root) then Nil
    else
      val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .filter: p =>
            Files.isRegularFile(p) && matcher.matches(root.relativize(p))
          .toList
      finally stream.close()

  /** Project root = current working directory. The whole CLI assumes cwd is the captal project. */
  def projectRoot: Path = Paths.get("").toAbsolutePath
end FileGlob
