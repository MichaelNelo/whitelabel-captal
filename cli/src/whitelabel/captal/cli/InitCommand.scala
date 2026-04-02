package whitelabel.captal.cli

import java.nio.file.{Files, Paths}

import zio.*

import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}

/** Creates the location provisioning directory with template files. */
object InitCommand:

  private val LocationsDir = Paths.get("/etc/captal/locations")

  def run(slug: String): Task[Unit] =
    val baseDir = LocationsDir.resolve(slug)
    for
      _ <- Console.printLine(s"Creating location at $baseDir/ ...")
      _ <- createDirectories(baseDir)
      _ <- TemplateWriter.writeAllIfAbsent(baseDir, Catalog.initTemplates(slug))
      _ <- Console.printLine:
        s"""  location.yaml
           |  i18n/es.yaml
           |  i18n/en.yaml
           |  videos/       (empty — add video directories)
           |  promo/        (empty)
           |  assets/styles.css
           |  assets/brand-icon.svg
           |Done. Edit the files and run 'captal push $slug'""".stripMargin
    yield ()

  private def createDirectories(baseDir: java.nio.file.Path): Task[Unit] = ZIO.attempt:
    List("videos", "promo").foreach(d => Files.createDirectories(baseDir.resolve(d)))
