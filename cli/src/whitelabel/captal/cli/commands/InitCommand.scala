package whitelabel.captal.cli.commands

import java.nio.file.{Files, Paths}

import whitelabel.captal.cli.Output
import whitelabel.captal.cli.migrations.{CliState, SemVer}
import whitelabel.captal.cli.templates.{Catalog, TemplateWriter}
import zio.*

/** Initializes a captal project with shared/, locations/, and .agents/skills/ directories. */
object InitCommand:

  def run(claude: Boolean, cliVersion: String): Task[Unit] =
    for
      _ <- Output.header("Initializing captal project")
      _ <- createDirectories
      _ <- TemplateWriter.writeAllIfAbsent(Paths.get("shared"), Catalog.sharedTemplates)
      _ <- TemplateWriter.writeAllIfAbsent(Paths.get(".agents"), Catalog.skillsTemplates)
      _ <- ZIO.when(claude)(createClaudeSymlink)
      // Seed .captal/state.json with the current CLI version so fresh installs never get
      // schema-migration warnings — they just created the project with the right schema.
      _ <- CliState.save(CliState(SemVer.parseOrZero(cliVersion)))
      _ <- Output.detail("shared/surveys/     (email, profiling, location)")
      _ <- Output.detail("shared/advertisers/ (add <slug>.yaml files)")
      _ <- Output.detail("shared/captal.yaml  (configure AWS + infrastructure)")
      _ <- Output.detail("locations/          (add locations with 'captal locations add')")
      _ <- Output.detail(".agents/skills/     (AI agent instructions)")
      _ <- ZIO.when(claude)(Output.detail(".claude/skills/     (symlink to .agents/skills/)"))
      _ <- Output.success("Project initialized")
      _ <- Output.info("Edit shared/captal.yaml with your AWS config")
      _ <- Output.info("Then add a location: captal locations add <slug>")
    yield ()

  private val createDirectories: Task[Unit] = ZIO.attempt:
    Files.createDirectories(Paths.get("shared/surveys"))
    Files.createDirectories(Paths.get("shared/advertisers"))
    Files.createDirectories(Paths.get("locations"))
    Files.createDirectories(Paths.get(".agents/skills"))

  private val createClaudeSymlink: Task[Unit] = ZIO.attempt:
    val claudeDir = Paths.get(".claude")
    val claudeSkills = claudeDir.resolve("skills")
    val target = Paths.get("../.agents/skills")
    Files.createDirectories(claudeDir)
    if !Files.exists(claudeSkills) then
      Files.createSymbolicLink(claudeSkills, target)
end InitCommand
