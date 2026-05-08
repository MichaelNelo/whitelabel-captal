package whitelabel.captal.cli.commands

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, NoSuchKeyException}
import whitelabel.captal.cli.{CliError, Output}
import zio.*

/** Self-update the CLI jar from S3.
  *
  * Reads `s3://<bucket>/latest/version.txt`, compares against the running version, and if
  * different downloads `s3://<bucket>/latest/captal.jar` to the running jar's path. The Windows
  * batch wrapper just runs the jar so it's transparently picked up next invocation.
  *
  * Auth uses the AWS SDK default credential provider chain (env vars / AWS profile / EC2 role).
  * No `shared/captal.yaml` is required — this command runs from any directory.
  */
object UpdateCommand:

  def run(currentVersion: String, bucket: String, region: String): IO[CliError, Unit] =
    for
      _              <- Output.header(s"Checking for updates from s3://$bucket/latest/ ...")
      jarPath        <- locateRunningJar
      remoteVersion  <- fetchRemoteVersion(bucket, region)
      _              <- Output.info(s"Current: v$currentVersion   Latest: v$remoteVersion")
      _              <-
        if remoteVersion == currentVersion then
          Output.success("Already up to date")
        else
          downloadAndReplace(bucket, region, jarPath, remoteVersion)
    yield ()

  // ─── Locate the running jar ─────────────────────────────────────────────────

  private val locateRunningJar: IO[CliError, Path] = ZIO
    .attempt:
      val codeSource = classOf[UpdateCommand.type].getProtectionDomain.getCodeSource
      Option(codeSource).flatMap(cs => Option(cs.getLocation)) match
        case Some(url) =>
          val uri = url.toURI
          val p = Paths.get(uri)
          if Files.isRegularFile(p) && p.toString.endsWith(".jar") then
            p
          else
            throw new RuntimeException(
              s"Not running from a jar (got $p). 'captal update' only works on the published jar.")
        case None =>
          throw new RuntimeException("Cannot locate running jar (no code source)")
    .mapError(e => CliError.BuildFailed(s"locate jar: ${e.getMessage}"))

  // ─── Read latest/version.txt ─────────────────────────────────────────────────

  private def fetchRemoteVersion(bucket: String, region: String): IO[CliError, String] = ZIO
    .scoped:
      ZIO
        .fromAutoCloseable(ZIO.attemptBlocking(s3Client(region)))
        .flatMap: client =>
          ZIO.attemptBlocking:
            val req = GetObjectRequest.builder().bucket(bucket).key("latest/version.txt").build()
            val bytes = client.getObjectAsBytes(req).asByteArray()
            new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim
    .mapError:
      case _: NoSuchKeyException =>
        CliError.BuildFailed(
          s"s3://$bucket/latest/version.txt not found — re-publish the CLI to populate it")
      case e: Throwable =>
        CliError.AwsError("S3 getObject (version.txt)", e)

  // ─── Download new jar and replace in place ───────────────────────────────────

  private def downloadAndReplace(
      bucket: String,
      region: String,
      currentJar: Path,
      newVersion: String): IO[CliError, Unit] =
    val tempJar = currentJar.resolveSibling(currentJar.getFileName.toString + ".new")
    for
      _ <- Output.info(s"Downloading v$newVersion to ${tempJar.getFileName} ...")
      _ <- ZIO
        .scoped:
          ZIO
            .fromAutoCloseable(ZIO.attemptBlocking(s3Client(region)))
            .flatMap: client =>
              ZIO.attemptBlocking:
                val req = GetObjectRequest.builder().bucket(bucket).key("latest/captal.jar").build()
                client.getObject(req, tempJar)
                ()
        .mapError(e => CliError.AwsError("S3 download captal.jar", e))
      _ <- ZIO
        .attemptBlocking:
          Files.move(
            tempJar,
            currentJar,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
          ()
        .catchAll: t =>
          // ATOMIC_MOVE may not be supported on the OS / FS. Fall back to plain replace.
          ZIO.attemptBlocking:
            Files.move(tempJar, currentJar, StandardCopyOption.REPLACE_EXISTING)
            ()
        .mapError: e =>
          CliError.BuildFailed(
            s"replace jar at $currentJar: ${e.getMessage}. The new jar is at $tempJar — replace manually if needed.")
      _ <- Output.success(s"Updated v$newVersion installed at $currentJar — re-run captal to use the new version")
    yield ()

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private def s3Client(region: String): S3Client = S3Client
    .builder()
    .region(Region.of(region))
    .build()

end UpdateCommand
