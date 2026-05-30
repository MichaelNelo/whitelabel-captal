package whitelabel.captal.cli.commands

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{JarURLConnection, URL}
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*

import whitelabel.captal.cli.Output
import zio.*

/** `captal infra init` and `captal infra update` — extract the AWS infrastructure bundle
  * embedded in this CLI JAR into `infrastructure/aws/` under cwd. Both have identical
  * skip-if-exists semantics; only the framing differs (init = first-time, update = sync new
  * files).
  *
  * The destination dir is keyed by provider (`aws/`), not by tool (`terraform/`), so future
  * provider bundles (e.g. `infrastructure/azure/` with Bicep) sit alongside without conflicts.
  *
  * Implementation notes:
  *   - Resources are read by enumerating the **JarFile** entries directly rather than via
  *     `FileSystems.newFileSystem`. Mill's `assembly` task prepends a launcher shim (~483 bytes)
  *     to the JAR, which trips up `ZipFileSystem`'s strict header validation with
  *     `ZipException: <hex>`. `JarFile` is lenient about leading bytes and handles this fine.
  *   - For the dev workflow (`mill cli.run`), the classpath is filesystem-based not JAR. We
  *     detect this and walk a directory instead.
  */
object InfraCommand:

  private val InfraDir = Paths.get("infrastructure/aws")
  private val ResourceRoot = "templates/infrastructure/aws"

  def init: Task[Unit] = sync(label = "Initializing")
  def update: Task[Unit] = sync(label = "Updating")

  private def sync(label: String): Task[Unit] =
    for
      _      <- Output.header(s"$label infrastructure/aws/")
      files  <- listBundledResources
      counts <- ZIO.foreach(files): (rel, bytes) =>
        ZIO.attempt:
          val target = InfraDir.resolve(rel)
          if Files.exists(target) then (rel, false)
          else
            Files.createDirectories(target.getParent)
            Files.write(target, bytes)
            (rel, true)
      added  = counts.count(_._2)
      skipped = counts.size - added
      _ <- Output.success(s"$label complete - added $added, skipped $skipped")
      _ <- ZIO.when(added > 0)(
        Output.info("Next: cd infrastructure/aws/ && tofu init && tofu apply -var-file=...")
      )
    yield ()

  /** Enumerate all classpath resources under [[ResourceRoot]] as `(relativePath, bytes)` pairs.
    *
    * Three classpath shapes are handled:
    *   1. **JAR (release)**: open the JAR via [[JarURLConnection]] and iterate entries with the
    *      `ResourceRoot/` prefix.
    *   2. **Filesystem (dev via `mill cli.run`)**: walk the resource directory.
    *   3. **Bundle missing**: hard fail with a helpful message pointing at the build task.
    */
  private val listBundledResources: Task[List[(String, Array[Byte])]] = ZIO.attemptBlocking:
    val rootUrl: URL = Option(getClass.getClassLoader.getResource(ResourceRoot)).getOrElse:
      throw new RuntimeException(
        s"Infrastructure bundle not found at $ResourceRoot in this CLI JAR. " +
          "Did you build with `./mill cli.syncInfrastructureBundle`?")

    rootUrl.getProtocol match
      case "jar"  => listFromJar(rootUrl)
      case "file" => listFromFilesystem(Paths.get(rootUrl.toURI))
      case other  =>
        throw new RuntimeException(s"Unsupported classpath URL protocol: $other ($rootUrl)")

  /** Open the JAR, iterate entries with prefix `ResourceRoot/`, return (relPath, bytes). */
  private def listFromJar(rootUrl: URL): List[(String, Array[Byte])] =
    val conn = rootUrl.openConnection().asInstanceOf[JarURLConnection]
    val jar: JarFile = conn.getJarFile
    try
      val prefix = s"$ResourceRoot/"
      jar
        .entries()
        .asScala
        .filter(e => !e.isDirectory && e.getName.startsWith(prefix))
        .map: entry =>
          val rel = entry.getName.substring(prefix.length)
          val bytes = readAllBytes(jar.getInputStream(entry))
          (rel, bytes)
        .toList
    finally jar.close()

  private def listFromFilesystem(root: Path): List[(String, Array[Byte])] =
    val walker = Files.walk(root)
    try
      walker
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map: p =>
          val rel = root.relativize(p).toString.replace('\\', '/')
          (rel, Files.readAllBytes(p))
        .toList
    finally walker.close()

  private def readAllBytes(in: InputStream): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var n = in.read(buf)
    while n > 0 do
      out.write(buf, 0, n)
      n = in.read(buf)
    in.close()
    out.toByteArray
end InfraCommand
