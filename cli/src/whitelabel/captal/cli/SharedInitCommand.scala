package whitelabel.captal.cli

import java.nio.file.{Files, Paths}

import zio.*

import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}

/** Creates the shared provisioning directory with survey and advertiser templates. */
object SharedInitCommand:

  private val BaseDir = Paths.get("/etc/captal/shared")

  def run: Task[Unit] =
    for
      _ <- Console.printLine(s"Creating shared resources at $BaseDir/ ...")
      _ <- createDirectories
      _ <- TemplateWriter.writeAllIfAbsent(BaseDir, Catalog.sharedInitTemplates)
      _ <- Console.printLine:
        s"""  surveys/email.yaml
           |  surveys/profiling.yaml
           |  surveys/location.yaml
           |  advertisers/   (empty — add <slug>.yaml files)
           |Done. Edit the files and run 'captal shared push'""".stripMargin
    yield ()

  private val createDirectories: Task[Unit] = ZIO.attempt:
    List("surveys", "advertisers").foreach(d => Files.createDirectories(BaseDir.resolve(d)))
