package whitelabel.captal.cli.commands

import java.nio.file.{Files, Paths}

import zio.*

import whitelabel.captal.cli.Output
import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}

/** Creates the shared provisioning directory with survey and advertiser templates in the current directory. */
object SharedInitCommand:

  private val BaseDir = Paths.get("shared")

  def run: Task[Unit] =
    for
      _ <- Output.header("Creating shared resources")
      _ <- createDirectories
      _ <- TemplateWriter.writeAllIfAbsent(BaseDir, Catalog.sharedInitTemplates)
      _ <- Output.detail("surveys/email.yaml, profiling.yaml, location.yaml")
      _ <- Output.detail("advertisers/   (add <slug>.yaml files)")
      _ <- Output.detail("captal.yaml    (configure AWS + infrastructure)")
      _ <- Output.detail("skills/        (agent instructions)")
      _ <- Output.success(s"Shared resources created at $BaseDir/")
      _ <- Output.info("Edit captal.yaml with your AWS config, then run 'captal shared push'")
    yield ()

  private val createDirectories: Task[Unit] = ZIO.attempt:
    List("surveys", "advertisers").foreach(d => Files.createDirectories(BaseDir.resolve(d)))
