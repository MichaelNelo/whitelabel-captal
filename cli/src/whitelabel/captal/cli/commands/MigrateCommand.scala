package whitelabel.captal.cli.commands

import java.nio.file.Path

import whitelabel.captal.cli.migrations.*
import whitelabel.captal.cli.{CliError, Output}
import zio.*

/** `captal migrate` — apply schema migrations to all project YAMLs.
  *
  *   - Scans every registered [[Migration]] against the file glob, computing the subset of ops
  *     that would actually change something.
  *   - Without flags: prints the plan, prompts for confirmation (especially if comments will be
  *     lost), then applies.
  *   - `--dry-run`: prints the plan but never writes.
  *   - `--yes`: skips both prompts. Suitable for CI.
  *
  * Idempotent: re-running after success is a no-op.
  */
object MigrateCommand:

  def run(dryRun: Boolean, yes: Boolean): ZIO[Any, CliError, Unit] =
    for
      _    <- Output.header("Captal schema migration")
      plan <- buildPlan
      _ <-
        if plan.isEmpty then
          // Even when there's nothing to apply, clear any stale state so the warning hook stops
          // firing in case the operator fixed conflicts manually after declining a prompt.
          Output.success("No pending migrations.") *> CliState.clear
        else applyPlan(plan, dryRun, yes)
    yield ()

  /** Per-file accumulated changes: each file may be touched by multiple migrations. */
  private final case class FilePlan(path: Path, ops: List[YamlOp], descriptions: List[String])

  private val buildPlan: ZIO[Any, CliError, List[FilePlan]] = ZIO
    .attemptBlocking:
      val root = FileGlob.projectRoot
      val groups = scala.collection.mutable.LinkedHashMap.empty[Path, FilePlan]
      Migrations.all.foreach: migration =>
        val files = FileGlob.resolve(migration.fileGlob, root)
        files.foreach: file =>
          YamlIo.read(file) match
            case Right(json) =>
              val pending = MigrationRunner.pendingOps(json, migration.ops)
              if pending.nonEmpty then
                val existing = groups.getOrElse(file, FilePlan(file, Nil, Nil))
                groups.update(
                  file,
                  existing.copy(
                    ops = existing.ops ++ pending,
                    descriptions = existing.descriptions ++ pending.map(_.describe)))
            case Left(_) => ()  // unparseable file — skip silently, migrate won't touch it
      groups.values.toList
    .mapError(t => CliError.ConfigError(s"Failed to build migration plan: ${t.getMessage}"))

  private def applyPlan(
      plan: List[FilePlan],
      dryRun: Boolean,
      yes: Boolean): ZIO[Any, CliError, Unit] =
    for
      _ <- Output.info(s"${plan.size} file(s) need migrations:")
      _ <- ZIO.foreachDiscard(plan): fp =>
        Output.detail(s"${FileGlob.projectRoot.relativize(fp.path)}:") *>
          ZIO.foreachDiscard(fp.descriptions)(d => Output.detail(s"  - $d"))
      filesWithComments = plan.filter(fp => YamlIo.hasComments(fp.path)).map(_.path)
      _ <- ZIO.when(filesWithComments.nonEmpty && !yes && !dryRun):
        Output.warn(
          s"Comments will be lost in ${filesWithComments.size} file(s) - the YAML round-trip doesn't preserve them.") *>
          confirm("Continue?")
      _ <- ZIO.when(!dryRun)(applyChanges(plan))
      _ <-
        if dryRun then Output.info("Dry run - no files were modified.")
        else
          // Migration succeeded → clear .captal/state.json so the warning hook stops firing.
          CliState.clear *> Output.success(s"Migrated ${plan.size} file(s).")
    yield ()

  private def applyChanges(plan: List[FilePlan]): ZIO[Any, CliError, Unit] =
    ZIO.foreachDiscard(plan): fp =>
      for
        result <- ZIO
          .attemptBlocking:
            YamlIo.read(fp.path).flatMap: json =>
              val (next, _) = MigrationRunner.applyOps(json, fp.ops)
              YamlIo.write(fp.path, next)
          .mapError(t =>
            CliError.ConfigError(s"Failed to migrate ${fp.path}: ${t.getMessage}"))
        _ <- result match
          case Right(_) =>
            ZIO.unit
          case Left(t) =>
            ZIO.fail(CliError.ConfigError(s"Failed to write ${fp.path}: ${t.getMessage}"))
      yield ()

  private def confirm(question: String): ZIO[Any, CliError, Unit] =
    for
      _      <- Console.print(s"$question [y/N]: ").orDie
      answer <- Console.readLine.orDie
      _ <-
        ZIO.unless(answer.trim.equalsIgnoreCase("y") || answer.trim.equalsIgnoreCase("yes"))(
          ZIO.fail(CliError.ConfigError("Aborted by user")))
    yield ()
end MigrateCommand
