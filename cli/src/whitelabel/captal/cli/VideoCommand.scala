package whitelabel.captal.cli

import java.nio.file.{Files, Path, Paths}

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import zio.*

import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}

/** Uploads a video to S3 and creates a YAML placeholder in the provision directory. */
object VideoCommand:

  private val BaseDir = Paths.get("/etc/captal")

  /** Upload an advertiser video. */
  def run(slug: String, advertiserSlug: String, videoPath: String): ZIO[S3Client, CliError, Unit] =
    for
      file     <- validateVideoFile(videoPath)
      bucket   <- readBucket
      fileName  = file.getFileName.toString
      videoSlug = fileName.replaceFirst("\\.[^.]+$", "")
      s3Key     = s"$slug/$fileName"

      _ <- Console.printLine(s"Uploading $fileName to s3://$bucket/$s3Key ...").orDie
      _ <- upload(bucket, s3Key, file)
      url = s"https://$bucket.s3.amazonaws.com/$s3Key"

      // Ensure advertiser directory and advertiser.yaml exist
      advDir = BaseDir.resolve(s"advertisers/$advertiserSlug")
      _ <- TemplateWriter
        .writeIfAbsent(advDir, Catalog.advertiser(advertiserSlug))
        .mapError(e => CliError.BuildFailed(s"write advertiser.yaml: $e"))

      // Write video YAML
      adTemplate = Catalog.videos.ad(videoSlug, url)
      wrote <- TemplateWriter
        .writeIfAbsent(advDir, adTemplate)
        .mapError(e => CliError.BuildFailed(s"write ${adTemplate.path}: $e"))
      yamlPath = advDir.resolve(adTemplate.path)
      _ <-
        if wrote then Console.printLine(s"Created $yamlPath").orDie
        else Console.printLine(s"  YAML already exists, skipping: $yamlPath").orDie
      _ <- Console.printLine(s"Edit duration, title, and survey fields, then run 'captal push $slug'").orDie
    yield ()

  /** Upload a promo video. */
  def runPromo(slug: String, videoPath: String): ZIO[S3Client, CliError, Unit] =
    for
      file     <- validateVideoFile(videoPath)
      bucket   <- readBucket
      fileName  = file.getFileName.toString
      videoSlug = fileName.replaceFirst("\\.[^.]+$", "")
      s3Key     = s"$slug/$fileName"

      _ <- Console.printLine(s"Uploading $fileName to s3://$bucket/$s3Key ...").orDie
      _ <- upload(bucket, s3Key, file)
      url = s"https://$bucket.s3.amazonaws.com/$s3Key"

      // Write promo YAML
      promoTemplate = Catalog.videos.promo(videoSlug, url)
      wrote <- TemplateWriter
        .writeIfAbsent(BaseDir, promoTemplate)
        .mapError(e => CliError.BuildFailed(s"write ${promoTemplate.path}: $e"))
      yamlPath = BaseDir.resolve(promoTemplate.path)
      _ <-
        if wrote then Console.printLine(s"Created $yamlPath").orDie
        else Console.printLine(s"  YAML already exists, skipping: $yamlPath").orDie
      _ <- Console.printLine(s"Edit duration and title fields, then run 'captal push $slug'").orDie
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private def validateVideoFile(path: String): IO[CliError, Path] =
    ZIO.attempt(Paths.get(path)).mapError(_ => CliError.InvalidVideoPath(path)).flatMap: p =>
      if !Files.exists(p) then ZIO.fail(CliError.InvalidVideoPath(s"file not found: $path"))
      else if !Files.isRegularFile(p) then ZIO.fail(CliError.InvalidVideoPath(s"not a file: $path"))
      else ZIO.succeed(p)

  private def readBucket: IO[CliError, String] =
    ZIO
      .attempt(sys.env.getOrElse("CAPTAL_S3_BUCKET", ""))
      .mapError(_ => CliError.MissingEnvVars(List("CAPTAL_S3_BUCKET")))
      .flatMap: bucket =>
        if bucket.isEmpty then ZIO.fail(CliError.MissingEnvVars(List("CAPTAL_S3_BUCKET")))
        else ZIO.succeed(bucket)

  private def upload(bucket: String, key: String, file: Path): ZIO[S3Client, CliError, Unit] =
    ZIO
      .serviceWithZIO[S3Client]: s3 =>
        ZIO.attemptBlocking:
          val contentType = if key.endsWith(".mp4") then "video/mp4"
            else if key.endsWith(".webm") then "video/webm"
            else "application/octet-stream"
          s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            file)
      .mapError(e => CliError.AwsError("S3 putObject", e))
      .unit
