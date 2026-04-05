package whitelabel.captal.cli.commands

import java.nio.file.{Files, Paths}

import zio.*

import whitelabel.captal.cli.Output
import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}

/** Creates a new location at locations/<slug>/ with template files. */
object LocationsAddCommand:

  def run(slug: String): Task[Unit] =
    val baseDir = Paths.get(s"locations/$slug")
    for
      _ <- Output.header(s"Adding location '$slug'")
      _ <- createDirectories(baseDir)
      _ <- TemplateWriter.writeAllIfAbsent(baseDir, Catalog.locationTemplates(slug))
      _ <- Output.detail("location.yaml")
      _ <- Output.detail("i18n/es.yaml, i18n/en.yaml")
      _ <- Output.detail("videos/       (add video directories)")
      _ <- Output.detail("promo/        (add promo YAMLs)")
      _ <- Output.detail("assets/styles.css, brand-icon.svg")
      _ <- Output.success(s"Location created at $baseDir/")
      _ <- Output.info(s"Edit the files and run 'captal locations push $slug'")
    yield ()

  private def createDirectories(baseDir: java.nio.file.Path): Task[Unit] = ZIO.attempt:
    List("videos", "promo").foreach(d => Files.createDirectories(baseDir.resolve(d)))
