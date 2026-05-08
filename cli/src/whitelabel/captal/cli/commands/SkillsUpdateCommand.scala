package whitelabel.captal.cli.commands

import java.nio.file.{Files, Paths}

import whitelabel.captal.cli.Output
import whitelabel.captal.cli.templates.{Catalog, Template, TemplateWriter}
import zio.*

/** Sync the skills bundled with this CLI version into `.agents/skills/`. Adds any skills that
  * exist in the CLI but not on disk; existing skills are left alone (no overwrites). Useful
  * after `captal update` brings new skills via a CLI upgrade.
  */
object SkillsUpdateCommand:

  def run: Task[Unit] =
    val baseDir = Paths.get(".agents")
    val templates = Catalog.skillsTemplates
    for
      _      <- Output.header(s"Syncing ${templates.size} skill(s) into $baseDir/skills/")
      added  <- ZIO.foreach(templates) { tpl =>
        TemplateWriter.writeIfAbsent(baseDir, tpl).map(written => tpl -> written)
      }
      writtenList = added.collect { case (t, true) => t }
      skippedList = added.collect { case (t, false) => t }
      _ <- ZIO.foreachDiscard(writtenList)(t => Output.success(s"Added ${t.path}"))
      _ <- ZIO.when(skippedList.nonEmpty)(
        Output.detail(s"Skipped ${skippedList.size} existing skill(s)"))
      _ <- Output.info(
        s"Done — added ${writtenList.size}, kept ${skippedList.size} as-is. Re-run after `captal update` to pick up new skills.")
    yield ()
end SkillsUpdateCommand
