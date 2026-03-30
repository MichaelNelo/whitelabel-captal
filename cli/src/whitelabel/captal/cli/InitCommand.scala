package whitelabel.captal.cli

import java.nio.file.{Files, Paths}

import zio.*

import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}

/** Creates the provisioning directory structure with template files. */
object InitCommand:

  private val BaseDir = Paths.get("/etc/captal")

  def run(slug: String): Task[Unit] =
    for
      _ <- Console.printLine(s"Creating project at $BaseDir/ ...")
      _ <- createDirectories
      _ <- TemplateWriter.writeAllIfAbsent(BaseDir, Catalog.initTemplates(slug))
      _ <- Console.printLine:
        s"""  location.yaml
           |  i18n/es.yaml
           |  i18n/en.yaml
           |  surveys/email.yaml
           |  surveys/profiling.yaml
           |  surveys/location.yaml
           |  advertisers/   (empty)
           |  promo/         (empty)
           |  assets/styles.css
           |  assets/brand-icon.svg
           |Done. Edit the files and run 'captal push $slug'""".stripMargin
    yield ()

  private val createDirectories: Task[Unit] = ZIO.attempt:
    List("advertisers", "promo").foreach(d => Files.createDirectories(BaseDir.resolve(d)))
