package millbuild

import com.goyeau.mill.scalafix.ScalafixModule
import mill.*, scalalib.*, scalafmt.*, scalajslib.*

// ─────────────────────────────────────────────────────────────────────────────
// Base traits
// ─────────────────────────────────────────────────────────────────────────────

trait BaseModule extends ScalaModule with ScalafmtModule with ScalafixModule:
  def scalaVersion = "3.8.0"
  def scalacOptions = Seq("-Wunused:imports,locals,explicits,implicits")

  /** Every jar's `META-INF/services` registrations in the assembly, one per line, where Mill's
    * defaults keep one jar's file and drop the rest's providers: `grpc-netty-shaded`'s
    * `io.grpc.NameResolverProvider` would hide `grpc-core`'s DNS resolver, and no gRPC channel to a
    * host name could be built. Separated by a newline, since a file may not end in one.
    */
  def assemblyRules =
    super.assemblyRules :+ Assembly.Rule.AppendPattern("META-INF/services/.*", "\n")

trait BaseJSModule extends ScalaJSModule with ScalafmtModule with ScalafixModule:
  def scalaVersion = "3.8.0"
  def scalaJSVersion = "1.20.1"
  def scalacOptions = Seq("-Wunused:imports,locals,explicits,implicits")

/** A module's release: the tag git-cliff computes for it, and the one it was last released under.
  *
  * @param tag
  *   The tag the next release goes out under, such as `api/v1.3.0`. The same as `previousTag` when
  *   nothing since that release bumps the version.
  * @param previousTag
  *   The last tag the module's `cliff.toml` matches, `None` before its first release.
  */
final case class ReleaseVersion(tag: String, previousTag: Option[String])
    derives upickle.default.ReadWriter:
  /** The version alone, `1.3.0`, which is what an image is tagged with. */
  def version: String = tag.replaceFirst("^.*?v(?=[0-9])", "")

  /** Whether anything since the last release bumps the version. */
  def changed: Boolean = !previousTag.contains(tag)

/** A module released under its own version, tagged `<module>/v<version>`, computed by git-cliff from
  * the conventional commits since the module's last tag. Rules come from the module's `cliff.toml`.
  * Counted paths: the folders and source folders of the module and of its dependencies, plus
  * [[versionFiles]].
  */
trait Released extends JavaModule:

  /** Files outside those folders that count toward the release, such as an image's Dockerfile. */
  def versionFiles: T[Seq[PathRef]] = Task(Seq.empty[PathRef])

  // `--context`, not `--bumped-version`: git-cliff 2.13 and 2.14 ignore `--include-path` for the latter.
  def releaseVersion: T[ReleaseVersion] = Task.Uncached:
    val workspace = mill.api.BuildCtx.workspaceRoot
    val folders = transitiveModuleDeps.map(_.moduleDir) ++
      Task.traverse(transitiveModuleDeps)(_.sources)().flatten.map(_.path)
    val paths =
      folders.map(folder => s"${folder.relativeTo(workspace)}/**").distinct ++
        versionFiles().map(_.path.relativeTo(workspace).toString)
    val result =
      try
        os.proc(
          "git-cliff",
          "--config",
          moduleDir / "cliff.toml",
          paths.flatMap(path => Seq("--include-path", path)),
          "--bump",
          "--unreleased",
          "--context")
          .call(cwd = workspace, check = false, stderr = os.Pipe)
      catch
        case _: java.io.IOException =>
          sys.error(
            "[version] git-cliff is not on the PATH Mill runs with. devenv installs it: reload the shell, then `mill shutdown` so the daemon starts with it.")
    if result.exitCode != 0 then
      sys.error(s"[version] git-cliff failed for ${moduleSegments.render}: ${result.err.trim()}")
    val release = ujson.read(result.out.text())(0)
    ReleaseVersion(
      tag = release("version").str,
      previousTag = release.obj.get("previous").flatMap(_.objOpt).flatMap(_.get("version")).flatMap(_.strOpt))

// ─────────────────────────────────────────────────────────────────────────────
// Core: JVM + JS (shared sources)
// ─────────────────────────────────────────────────────────────────────────────

trait CoreDepsJVM extends ScalaModule:
  def mvnDeps = Seq(
    mvn"org.typelevel::cats-core:2.12.0",
    mvn"io.circe::circe-core:0.14.10",
    mvn"io.circe::circe-generic:0.14.10",
    mvn"io.circe::circe-parser:0.14.10",
    mvn"io.taig::babel-core:0.5.3",
    mvn"io.taig::babel-generic:0.5.3",
    mvn"io.taig::babel-circe:0.5.3"
  )

trait CoreDepsJS extends ScalaJSModule:
  def mvnDeps = Seq(
    mvn"org.typelevel::cats-core::2.12.0",
    mvn"io.circe::circe-core::0.14.10",
    mvn"io.circe::circe-generic::0.14.10",
    mvn"io.circe::circe-parser::0.14.10",
    mvn"io.github.cquiroz::scala-java-time::2.6.0",
    mvn"io.taig::babel-core::0.5.3",
    mvn"io.taig::babel-generic::0.5.3",
    mvn"io.taig::babel-circe::0.5.3"
  )
