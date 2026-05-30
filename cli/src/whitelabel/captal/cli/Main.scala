package whitelabel.captal.cli

import whitelabel.captal.cli.commands.*
import whitelabel.captal.cli.migrations.{CliState, MigrationScanner, SemVer}
import zio.*
import zio.cli.*

enum CaptalCommand:
  case Init(claude: Boolean)
  case SharedPush
  case LocationsAdd(slug: String)
  case LocationsPush(slug: String)
  case LocationsPushAll
  case LocationsDeprovision(slug: String, yes: Boolean)
  case VideoAdd(slug: String, advertiser: String, file: String)
  case PromoAdd(slug: String, file: String)
  case SkillsUpdate
  case Update(baseUrl: String)
  case Migrate(dryRun: Boolean, yes: Boolean)
  case InfraInit
  case InfraUpdate

object Main extends ZIOCliDefault:

  private val cliVersion = "2.2.1"
  private val slugArg = Args.text("slug")

  // ─── init ─────────────────────────────────────────────────────────────────

  private val init: Command[CaptalCommand] = Command(
    "init",
    Options.boolean("claude").alias("c"),
    Args.none)
    .withHelp(HelpDoc.p("Initialize captal project (shared/, locations/, .agents/skills/)"))
    .map(claude => CaptalCommand.Init(claude))

  // ─── shared ───────────────────────────────────────────────────────────────

  private val sharedPush: Command[CaptalCommand] = Command("push", Options.none, Args.none)
    .withHelp(HelpDoc.p("Deploy shared resources to AWS via ephemeral ECS task"))
    .map(_ => CaptalCommand.SharedPush)

  private val shared: Command[CaptalCommand] = Command("shared", Options.none, Args.none)
    .withHelp(HelpDoc.p("Manage shared resources (surveys + advertisers)"))
    .subcommands(sharedPush)

  // ─── locations ────────────────────────────────────────────────────────────

  private val locationsAdd: Command[CaptalCommand] = Command("add", Options.none, slugArg)
    .withHelp(HelpDoc.p("Add a new location at locations/<slug>/"))
    .map(CaptalCommand.LocationsAdd(_))

  private val locationsPush: Command[CaptalCommand] = Command("push", Options.none, slugArg)
    .withHelp(
      HelpDoc.p(
        "Deploy a location to AWS (S3 + ECS + ALB). Pass a specific slug, or the literal 'all' to deploy every location under locations/ in sequence (useful after bumping the API base image in shared/captal.yaml)."))
    .map:
      case "all" =>
        CaptalCommand.LocationsPushAll
      case slug =>
        CaptalCommand.LocationsPush(slug)

  private val locationsDeprovision: Command[CaptalCommand] = Command(
    "deprovision",
    Options.boolean("yes").alias("y"),
    slugArg)
    .withHelp(
      HelpDoc.p(
        "Tear down AWS infrastructure for a location (ECS service, ALB rule + target group, task defs, log group, ECR image tags, S3 assets, CloudFront cache). The local locations/<slug>/ directory is kept so the same slug can be re-provisioned with `push`. Pass --yes to skip the confirmation prompt."))
    .map((yes, slug) => CaptalCommand.LocationsDeprovision(slug, yes))

  private val locations: Command[CaptalCommand] = Command("locations", Options.none, Args.none)
    .withHelp(HelpDoc.p("Manage locations"))
    .subcommands(locationsAdd, locationsPush, locationsDeprovision)

  // ─── video ────────────────────────────────────────────────────────────────

  private val videoAdd: Command[CaptalCommand] = Command(
    "add",
    Options.none,
    slugArg ++ Args.text("advertiser") ++ Args.text("file"))
    .withHelp(HelpDoc.p("Upload advertiser video to S3 and create YAML"))
    .map((slug, adv, file) => CaptalCommand.VideoAdd(slug, adv, file))

  private val promoAdd: Command[CaptalCommand] = Command(
    "add-promo",
    Options.none,
    slugArg ++ Args.text("file"))
    .withHelp(HelpDoc.p("Upload promo video to S3 and create YAML"))
    .map((slug, file) => CaptalCommand.PromoAdd(slug, file))

  private val video: Command[CaptalCommand] = Command("video", Options.none, Args.none)
    .withHelp(HelpDoc.p("Manage videos"))
    .subcommands(videoAdd, promoAdd)

  // ─── skills ───────────────────────────────────────────────────────────────

  private val skillsUpdate: Command[CaptalCommand] = Command("update", Options.none, Args.none)
    .withHelp(
      HelpDoc.p(
        "Add any skills bundled with this CLI version that are missing from .agents/skills/. Existing skills are left alone."))
    .map(_ => CaptalCommand.SkillsUpdate)

  private val skills: Command[CaptalCommand] = Command("skills", Options.none, Args.none)
    .withHelp(HelpDoc.p("Manage AI agent skills"))
    .subcommands(skillsUpdate)

  // ─── update (self) ────────────────────────────────────────────────────────

  private val updateUrlOpt = Options
    .text("url")
    .alias("u")
    .withDefault("https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest")

  private val update: Command[CaptalCommand] = Command("update", updateUrlOpt, Args.none)
    .withHelp(
      HelpDoc.p(
        "Self-update the CLI jar from the public release URL (default: latest from captal-cli-releases-dev). Compares <url>/version.txt against the running version and downloads <url>/captal.jar if newer. No AWS credentials needed."))
    .map(url => CaptalCommand.Update(url))

  // ─── migrate (schema migrations) ──────────────────────────────────────────

  private val migrate: Command[CaptalCommand] = Command(
    "migrate",
    Options.boolean("dry-run") ++ Options.boolean("yes").alias("y"),
    Args.none)
    .withHelp(
      HelpDoc.p(
        "Apply schema migrations to project YAMLs (e.g. locations/*/location.yaml). Idempotent - safe to re-run. --dry-run shows changes without writing; --yes skips the comments-will-be-lost prompt (CI-friendly). Use git to recover from mistakes."))
    .map((dryRun, yes) => CaptalCommand.Migrate(dryRun, yes))

  // ─── infra (embedded Terraform bundle) ────────────────────────────────────

  private val infraInit: Command[CaptalCommand] = Command("init", Options.none, Args.none)
    .withHelp(
      HelpDoc.p(
        "Create infrastructure/aws/ in the project root by extracting the AWS Terraform bundle from this CLI. Existing files are skipped. After running, customize backend.tf + your environments/<env>/terraform.tfvars per your AWS account, then `tofu init && tofu apply`."))
    .map(_ => CaptalCommand.InfraInit)

  private val infraUpdate: Command[CaptalCommand] = Command("update", Options.none, Args.none)
    .withHelp(
      HelpDoc.p(
        "Add any infrastructure files bundled with this CLI version that are missing from infrastructure/aws/. Existing files are left alone (operator customizations preserved). Same idempotent skip-if-exists semantics as `captal skills update`."))
    .map(_ => CaptalCommand.InfraUpdate)

  private val infra: Command[CaptalCommand] = Command("infra", Options.none, Args.none)
    .withHelp(HelpDoc.p("Manage the embedded provider infrastructure bundle (currently AWS only; future providers will live under infrastructure/<provider>/)"))
    .subcommands(infraInit, infraUpdate)

  // ─── root ─────────────────────────────────────────────────────────────────

  private val captal: Command[CaptalCommand] = Command("captal", Options.none, Args.none)
    .subcommands(init, shared, locations, video, skills, update, migrate, infra)

  val cliApp: CliApp[Any, Any, CaptalCommand] =
    CliApp.make(
      name = "captal",
      version = cliVersion,
      summary = HelpDoc.Span.text("Whitelabel captive portal provisioning CLI"),
      command = captal,
      config = CliConfig.default.copy(finalCheckBuiltIn = false)
    ) { cmd =>
      val program: ZIO[Any, Any, CaptalCommand] =
        (
          cmd match
            case CaptalCommand.Init(claude) =>
              InitCommand.run(claude, cliVersion)

            case CaptalCommand.SharedPush =>
              SharedPushCommand.run.provide(CaptalConfig.layer, AwsLayers.ecs, AwsLayers.ecr)

            case CaptalCommand.LocationsAdd(slug) =>
              LocationsAddCommand.run(slug)

            case CaptalCommand.LocationsPush(slug) =>
              PushCommand
                .run(slug)
                .provide(
                  CaptalConfig.layer,
                  AwsLayers.s3,
                  AwsLayers.ecs,
                  AwsLayers.elbv2,
                  AwsLayers.ecr,
                  AwsLayers.cloudfront,
                  AwsLayers.cloudwatchLogs)

            case CaptalCommand.LocationsPushAll =>
              PushAllCommand
                .run
                .provide(
                  CaptalConfig.layer,
                  AwsLayers.s3,
                  AwsLayers.ecs,
                  AwsLayers.elbv2,
                  AwsLayers.ecr,
                  AwsLayers.cloudfront,
                  AwsLayers.cloudwatchLogs)

            case CaptalCommand.LocationsDeprovision(slug, yes) =>
              DeprovisionCommand
                .run(slug, yes)
                .provide(
                  CaptalConfig.layer,
                  AwsLayers.s3,
                  AwsLayers.ecs,
                  AwsLayers.elbv2,
                  AwsLayers.ecr,
                  AwsLayers.cloudfront,
                  AwsLayers.cloudwatchLogs)

            case CaptalCommand.VideoAdd(slug, advertiser, file) =>
              VideoCommand.run(slug, advertiser, file).provide(CaptalConfig.layer, AwsLayers.s3)

            case CaptalCommand.PromoAdd(slug, file) =>
              VideoCommand.runPromo(slug, file).provide(CaptalConfig.layer, AwsLayers.s3)

            case CaptalCommand.SkillsUpdate =>
              SkillsUpdateCommand.run

            case CaptalCommand.Update(baseUrl) =>
              UpdateCommand.run(cliVersion, baseUrl)

            case CaptalCommand.Migrate(dryRun, yes) =>
              MigrateCommand.run(dryRun, yes)

            case CaptalCommand.InfraInit   => InfraCommand.init
            case CaptalCommand.InfraUpdate => InfraCommand.update
        ).as(cmd)

      program.tapError:
        case e: whitelabel.captal.cli.CliError =>
          Output.error(e.message)
        case e: Throwable =>
          Output.error(e.getMessage)
        case e =>
          Output.error(e.toString)
    }

  /** Override the ZIOAppDefault entrypoint so we can wrap `cliApp.run` with the schema-migration
    * hook. Two paths:
    *
    *   - **First run post-update** (`state.version != currentCliVersion`): full project scan via
    *     [[MigrationScanner.fullScan]]. If conflicts are found, prompt the operator. Y → apply
    *     migrations inline (via [[MigrateCommand]]) then continue with the user's command. N →
    *     save the conflict list to state.json for cheap re-scans next time. Either way, the
    *     user's original command still runs afterwards.
    *   - **Subsequent run** (`state.version == current`): re-scan ONLY the cached `pendingFiles`.
    *     If some have been fixed manually, drop them from the list. If the list empties, delete
    *     the state file (self-cleanup). Otherwise, emit a warning before running the command.
    *
    * State-file IO is best-effort (`.ignore` / `.orElseSucceed`) so a busted state file never
    * blocks the actual command.
    */
  override def run: ZIO[zio.ZIOAppArgs, Any, Any] =
    val current = SemVer.parseOrZero(cliVersion)
    for
      args  <- ZIO.service[zio.ZIOAppArgs].map(_.getArgs.toList)
      state <- CliState.load
      _ <-
        if state.version != current then onFirstRunPostUpdate(current)
        else onSubsequentRun(state, current)
      out <- cliApp.run(args)
    yield out

  /** Full project scan + interactive prompt. Runs BEFORE the user's command so migrations are
    * applied first if the operator answers Y.
    */
  private def onFirstRunPostUpdate(current: SemVer): UIO[Unit] =
    for
      conflicts <- MigrationScanner.fullScan
      _ <-
        if conflicts.isEmpty then CliState.save(CliState(current, Nil))
        else promptApplyNow(conflicts, current)
    yield ()

  /** Interactive y/N. Default (empty answer) → Y. Y → run migrate inline + clear state.
    * N → save state with the conflict list cached for subsequent invocations.
    */
  private def promptApplyNow(conflicts: List[String], current: SemVer): UIO[Unit] =
    Output.warn(
      s"${conflicts.size} file(s) need schema migrations - your YAMLs may be out of date:") *>
      ZIO.foreachDiscard(conflicts)(p => Output.detail(s"  - $p")) *>
      Console.print("Apply now? [Y/n]: ").orDie *>
      Console.readLine.orDie.flatMap: answer =>
        if answer.trim.isEmpty || answer.trim.toLowerCase.startsWith("y") then
          MigrateCommand
            .run(dryRun = false, yes = true)
            .catchAll(e => Output.error(e.message)) *> CliState.clear
        else
          CliState.save(CliState(current, conflicts)) *>
            Output.info("Skipped. Run `captal migrate` later to apply.")

  /** Re-scan ONLY the cached files. If the list empties, delete state.json. Otherwise update it
    * and emit a warning.
    */
  private def onSubsequentRun(state: CliState, current: SemVer): UIO[Unit] =
    if state.pendingFiles.isEmpty then ZIO.unit
    else
      for
        stillPending <- MigrationScanner.rescanFiles(state.pendingFiles)
        _ <-
          if stillPending.isEmpty then
            CliState.clear *> Output.success("All pending schema migrations resolved.")
          else
            CliState.save(CliState(current, stillPending)) *> printPendingWarning(stillPending)
      yield ()

  private def printPendingWarning(files: List[String]): UIO[Unit] =
    Output.warn(s"${files.size} file(s) with pending schema migrations:") *>
      ZIO.foreachDiscard(files)(p => Output.detail(s"  - $p")) *>
      Output.info("Run `captal migrate` to apply them. Use `--dry-run` to preview first.")
end Main
