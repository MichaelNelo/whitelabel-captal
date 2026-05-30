package whitelabel.captal.cli.commands

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*

import whitelabel.captal.cli.{CliError, Output}
import zio.*

/** Push every location found in the local `locations/` directory. Iterates serially (the
  * `docker build` + S3 sync per location is heavy; running in parallel would saturate the local
  * Docker daemon and conflict on ECR layer cache). On any failure aborts and reports which slug
  * failed.
  */
object PushAllCommand:

  type Env = PushCommand.Env

  private val LocationsDir = Paths.get("locations")

  def run: ZIO[Env, CliError, Unit] =
    for
      slugs <- discoverLocalSlugs
      _     <- Output.header(s"Pushing ${slugs.size} location(s): ${slugs.mkString(", ")}")
      _     <-
        ZIO.foreachDiscard(slugs.zipWithIndex): (slug, idx) =>
          Output.header(s"[${idx + 1}/${slugs.size}] $slug") *> PushCommand.run(slug)
      _ <- Output.success(s"Push complete for ${slugs.size} location(s)")
    yield ()

  /** Discover slugs from `locations/<slug>/location.yaml`. Skips entries without that file (e.g. a
    * stray `.git` dir or scratch folder).
    */
  private def discoverLocalSlugs: IO[CliError, List[String]] = ZIO
    .attempt:
      if !Files.exists(LocationsDir) then
        throw new RuntimeException(
          s"$LocationsDir/ not found in cwd — run from your captal project root")
      val children = Files.list(LocationsDir).iterator().asScala.toList
      val slugs =
        children
          .filter(Files.isDirectory(_))
          .filter(p => Files.exists(p.resolve("location.yaml")))
          .map(_.getFileName.toString)
          .sorted
      if slugs.isEmpty then
        throw new RuntimeException(
          s"No locations found in $LocationsDir/ — add at least one with `captal locations add <slug>`")
      slugs
    .mapError(e => CliError.ConfigError(e.getMessage))
end PushAllCommand
