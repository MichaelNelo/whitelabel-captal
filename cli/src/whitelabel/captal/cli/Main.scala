package whitelabel.captal.cli

import zio.*
import zio.cli.*
import whitelabel.captal.cli.commands.*

enum CaptalCommand:
  case Init(claude: Boolean)
  case SharedPush
  case LocationsAdd(slug: String)
  case LocationsPush(slug: String)
  case VideoAdd(slug: String, advertiser: String, file: String)
  case PromoAdd(slug: String, file: String)

object Main extends ZIOCliDefault:

  private val slugArg = Args.text("slug")

  // ─── init ─────────────────────────────────────────────────────────────────

  private val init: Command[CaptalCommand] =
    Command("init", Options.boolean("claude").alias("c"), Args.none)
      .withHelp(HelpDoc.p("Initialize captal project (shared/, locations/, .agents/skills/)"))
      .map(claude => CaptalCommand.Init(claude))

  // ─── shared ───────────────────────────────────────────────────────────────

  private val sharedPush: Command[CaptalCommand] =
    Command("push", Options.none, Args.none)
      .withHelp(HelpDoc.p("Deploy shared resources to AWS via ephemeral ECS task"))
      .map(_ => CaptalCommand.SharedPush)

  private val shared: Command[CaptalCommand] =
    Command("shared", Options.none, Args.none)
      .withHelp(HelpDoc.p("Manage shared resources (surveys + advertisers)"))
      .subcommands(sharedPush)

  // ─── locations ────────────────────────────────────────────────────────────

  private val locationsAdd: Command[CaptalCommand] =
    Command("add", Options.none, slugArg)
      .withHelp(HelpDoc.p("Add a new location at locations/<slug>/"))
      .map(CaptalCommand.LocationsAdd(_))

  private val locationsPush: Command[CaptalCommand] =
    Command("push", Options.none, slugArg)
      .withHelp(HelpDoc.p("Deploy location to AWS (S3 + ECS + ALB)"))
      .map(CaptalCommand.LocationsPush(_))

  private val locations: Command[CaptalCommand] =
    Command("locations", Options.none, Args.none)
      .withHelp(HelpDoc.p("Manage locations"))
      .subcommands(locationsAdd, locationsPush)

  // ─── video ────────────────────────────────────────────────────────────────

  private val videoAdd: Command[CaptalCommand] =
    Command("add", Options.none, slugArg ++ Args.text("advertiser") ++ Args.text("file"))
      .withHelp(HelpDoc.p("Upload advertiser video to S3 and create YAML"))
      .map((slug, adv, file) => CaptalCommand.VideoAdd(slug, adv, file))

  private val promoAdd: Command[CaptalCommand] =
    Command("add-promo", Options.none, slugArg ++ Args.text("file"))
      .withHelp(HelpDoc.p("Upload promo video to S3 and create YAML"))
      .map((slug, file) => CaptalCommand.PromoAdd(slug, file))

  private val video: Command[CaptalCommand] =
    Command("video", Options.none, Args.none)
      .withHelp(HelpDoc.p("Manage videos"))
      .subcommands(videoAdd, promoAdd)

  // ─── root ─────────────────────────────────────────────────────────────────

  private val captal: Command[CaptalCommand] =
    Command("captal", Options.none, Args.none)
      .subcommands(init, shared, locations, video)

  val cliApp: CliApp[Any, Any, CaptalCommand] = CliApp.make(
    name = "captal",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Whitelabel captive portal provisioning CLI"),
    command = captal,
    config = CliConfig.default.copy(finalCheckBuiltIn = false)
  ) { cmd =>
    val program: ZIO[Any, Any, CaptalCommand] = (cmd match
      case CaptalCommand.Init(claude) =>
        InitCommand.run(claude)

      case CaptalCommand.SharedPush =>
        SharedPushCommand.run
          .provide(CaptalConfig.layer, AwsLayers.ecs)

      case CaptalCommand.LocationsAdd(slug) =>
        LocationsAddCommand.run(slug)

      case CaptalCommand.LocationsPush(slug) =>
        PushCommand.run(slug)
          .provide(CaptalConfig.layer, AwsLayers.s3, AwsLayers.ecs, AwsLayers.elbv2)

      case CaptalCommand.VideoAdd(slug, advertiser, file) =>
        VideoCommand.run(slug, advertiser, file)
          .provide(CaptalConfig.layer, AwsLayers.s3)

      case CaptalCommand.PromoAdd(slug, file) =>
        VideoCommand.runPromo(slug, file)
          .provide(CaptalConfig.layer, AwsLayers.s3)

    ).as(cmd)

    program.tapError:
      case e: whitelabel.captal.cli.CliError => Output.error(e.message)
      case e: Throwable => Output.error(e.getMessage)
      case e => Output.error(e.toString)
  }
