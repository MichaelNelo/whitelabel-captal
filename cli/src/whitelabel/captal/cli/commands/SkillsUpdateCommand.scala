package whitelabel.captal.cli.commands

import java.nio.file.Paths

import whitelabel.captal.cli.Output
import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}
import zio.*

/** Overwrite `.agents/skills/` with the skills bundled in this CLI version.
  *
  * Always writes (no skip-if-exists) so operators get the latest copy after every `captal update` —
  * that includes bug fixes, new sections, and renames within an existing skill. Any local edits to
  * bundled skills will be lost; if the operator wants local customizations they should be kept in a
  * separate file outside `.agents/skills/<bundled-name>/`.
  */
object SkillsUpdateCommand:

  def run: Task[Unit] =
    val baseDir = Paths.get(".agents")
    val templates = Catalog.skillsTemplates
    for
      _       <- Output.header(s"Syncing ${templates.size} skill(s) into $baseDir/skills/")
      results <-
        ZIO.foreach(templates) { tpl =>
          TemplateWriter.writeOverwrite(baseDir, tpl).map(existed => tpl -> existed)
        }
      updated = results.collect { case (t, true) =>
        t
      }
      added = results.collect { case (t, false) =>
        t
      }
      _ <- ZIO.foreachDiscard(added)(t => Output.success(s"Added ${t.path}"))
      _ <- ZIO.foreachDiscard(updated)(t => Output.detail(s"Updated ${t.path}"))
      _ <- Output.info(
        s"Done — added ${added.size}, updated ${updated
            .size}. Existing skill files were overwritten.")
    yield ()
  end run
end SkillsUpdateCommand
