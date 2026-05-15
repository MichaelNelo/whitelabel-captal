package whitelabel.captal.cli

import whitelabel.captal.cli.commands.*
import zio.*
import zio.cli.*

enum CaptalCommand:
  case Init(claude: Boolean)
  case SharedPush
  case LocationsAdd(slug: String)
  case LocationsPush(slug: String)
  case LocationsPushAll
  case VideoAdd(slug: String, advertiser: String, file: String)
  case PromoAdd(slug: String, file: String)
  case SkillsUpdate
  case Update(baseUrl: String)

object Main extends ZIOCliDefault:

  private val cliVersion = "1.6.0"
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
    .withHelp(HelpDoc.p("Deploy location to AWS (S3 + ECS + ALB)"))
    .map(CaptalCommand.LocationsPush(_))

  private val locationsPushAll: Command[CaptalCommand] = Command(
    "push-all",
    Options.none,
    Args.none)
    .withHelp(
      HelpDoc.p(
        "Deploy ALL locations under locations/<slug>/ in sequence. Useful after bumping the API base image version in shared/captal.yaml."))
    .map(_ => CaptalCommand.LocationsPushAll)

  private val locations: Command[CaptalCommand] = Command("locations", Options.none, Args.none)
    .withHelp(HelpDoc.p("Manage locations"))
    .subcommands(locationsAdd, locationsPush, locationsPushAll)

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

  // ─── root ─────────────────────────────────────────────────────────────────

  private val captal: Command[CaptalCommand] = Command("captal", Options.none, Args.none)
    .subcommands(init, shared, locations, video, skills, update)

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
              InitCommand.run(claude)

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

            case CaptalCommand.VideoAdd(slug, advertiser, file) =>
              VideoCommand.run(slug, advertiser, file).provide(CaptalConfig.layer, AwsLayers.s3)

            case CaptalCommand.PromoAdd(slug, file) =>
              VideoCommand.runPromo(slug, file).provide(CaptalConfig.layer, AwsLayers.s3)

            case CaptalCommand.SkillsUpdate =>
              SkillsUpdateCommand.run

            case CaptalCommand.Update(baseUrl) =>
              UpdateCommand.run(cliVersion, baseUrl)
        ).as(cmd)

      program.tapError:
        case e: whitelabel.captal.cli.CliError =>
          Output.error(e.message)
        case e: Throwable =>
          Output.error(e.getMessage)
        case e =>
          Output.error(e.toString)
    }
end Main
