package whitelabel.captal.cli.commands

import java.io.{BufferedInputStream, FileOutputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import whitelabel.captal.cli.{CliError, Output}
import zio.*

/** Self-update the CLI jar from the public S3 release bucket.
  *
  * Reads `<base-url>/version.txt`, compares against the running version, and if different downloads
  * `<base-url>/captal.jar` to the running jar's path. The wrapper scripts (`captal` shell,
  * `captal.bat`) just `java -jar captal.jar` so the next invocation picks up the new jar
  * automatically.
  *
  * Uses anonymous HTTP fetch - no AWS credentials needed. The bucket must allow public read on the
  * `latest` and `v...` prefixes (via Terraform module `cli-releases`).
  */
object UpdateCommand:

  def run(currentVersion: String, baseUrl: String): IO[CliError, Unit] =
    val normalized = baseUrl.stripSuffix("/")
    for
      _             <- Output.header(s"Checking for updates from $normalized/ ...")
      jarPath       <- locateRunningJar
      remoteVersion <- httpGetString(s"$normalized/version.txt")
      _             <- Output.info(s"Current: v$currentVersion   Latest: v$remoteVersion")
      _             <-
        if remoteVersion == currentVersion then
          Output.success("Already up to date")
        else
          downloadAndReplace(s"$normalized/captal.jar", jarPath, remoteVersion)
    yield ()

  // ─── Locate the running jar ─────────────────────────────────────────────────

  private val locateRunningJar: IO[CliError, Path] = ZIO
    .attempt:
      val codeSource = classOf[UpdateCommand.type].getProtectionDomain.getCodeSource
      Option(codeSource).flatMap(cs => Option(cs.getLocation)) match
        case Some(url) =>
          val p = Paths.get(url.toURI)
          if Files.isRegularFile(p) && p.toString.endsWith(".jar") then
            p
          else
            throw new RuntimeException(
              s"Not running from a jar (got $p). 'captal update' only works on the published jar.")
        case None =>
          throw new RuntimeException("Cannot locate running jar (no code source)")
    .mapError(e => CliError.BuildFailed(s"locate jar: ${e.getMessage}"))

  // ─── HTTP helpers ────────────────────────────────────────────────────────────

  private def httpGetString(url: String): IO[CliError, String] = ZIO
    .attemptBlocking:
      val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(15000)
      conn.connect()
      val code = conn.getResponseCode
      if code != 200 then
        conn.disconnect()
        throw new RuntimeException(s"HTTP $code from $url")
      val body =
        try new String(conn.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
        finally conn.disconnect()
      body
    .mapError(e => CliError.BuildFailed(s"GET $url: ${e.getMessage}"))

  private def httpDownload(url: String, target: Path): IO[CliError, Unit] = ZIO
    .attemptBlocking:
      val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(15000)
      conn.setReadTimeout(60000)
      conn.connect()
      val code = conn.getResponseCode
      if code != 200 then
        conn.disconnect()
        throw new RuntimeException(s"HTTP $code from $url")
      try
        val in = new BufferedInputStream(conn.getInputStream)
        val out = new FileOutputStream(target.toFile)
        try
          val buf = new Array[Byte](64 * 1024)
          var n = in.read(buf)
          while n != -1 do
            out.write(buf, 0, n)
            n = in.read(buf)
        finally
          out.close()
          in.close()
      finally
        conn.disconnect()
    .mapError(e => CliError.BuildFailed(s"download $url: ${e.getMessage}"))

  // ─── Download new jar; swap is deferred to the wrapper script ───────────────
  //
  // On Windows the running JVM holds an exclusive lock on the JAR, so we cannot replace it
  // in-place. Cross-platform fix: download to `<jar>.new` and let the `captal` / `captal.bat`
  // wrapper rename it on the NEXT invocation (the wrappers do `mv -f .new -> .jar` before
  // launching Java). Linux/macOS could replace in-place but using the same flow keeps
  // behavior consistent.

  private def downloadAndReplace(
      jarUrl: String,
      currentJar: Path,
      newVersion: String): IO[CliError, Unit] =
    val tempJar = currentJar.resolveSibling(currentJar.getFileName.toString + ".new")
    for
      _ <- Output.info(s"Downloading v$newVersion to ${tempJar.getFileName} ...")
      _ <- httpDownload(jarUrl, tempJar)
      _ <- Output.success(
        s"Update staged at $tempJar - the next `captal` invocation will swap it in automatically")
    yield ()

end UpdateCommand
