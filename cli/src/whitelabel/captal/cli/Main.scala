package whitelabel.captal.cli

import zio.*
import zio.cli.*
import whitelabel.captal.cli.commands.*

enum CaptalCommand:
  case SharedInit
  case SharedPush
  case LocationInit(slug: String)
  case LocationPush(slug: String)
  case VideoAdd(slug: String, advertiser: String, file: String)
  case PromoAdd(slug: String, file: String)

object Main extends ZIOCliDefault:

  private val slugArg = Args.text("slug")

  // ─── shared ─────────────────────────────────────────────────────────────────

  private val sharedInit: Command[CaptalCommand] =
    Command("init", Options.none, Args.none)
      .withHelp(HelpDoc.p("Create shared resources (surveys, advertisers, config)"))
      .map(_ => CaptalCommand.SharedInit)

  private val sharedPush: Command[CaptalCommand] =
    Command("push", Options.none, Args.none)
      .withHelp(HelpDoc.p("Deploy shared resources to AWS via ephemeral ECS task"))
      .map(_ => CaptalCommand.SharedPush)

  private val shared: Command[CaptalCommand] =
    Command("shared", Options.none, Args.none)
      .withHelp(HelpDoc.p("Manage shared resources (surveys + advertisers)"))
      .subcommands(sharedInit, sharedPush)

  // ─── location ───────────────────────────────────────────────────────────────

  private val locationInit: Command[CaptalCommand] =
    Command("init", Options.none, slugArg)
      .withHelp(HelpDoc.p("Create a new location at ./<slug>/"))
      .map(CaptalCommand.LocationInit(_))

  private val locationPush: Command[CaptalCommand] =
    Command("push", Options.none, slugArg)
      .withHelp(HelpDoc.p("Deploy location to AWS (S3 + ECS + ALB)"))
      .map(CaptalCommand.LocationPush(_))

  private val location: Command[CaptalCommand] =
    Command("location", Options.none, Args.none)
      .withHelp(HelpDoc.p("Manage locations"))
      .subcommands(locationInit, locationPush)

  // ─── video ──────────────────────────────────────────────────────────────────

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

  // ─── root ───────────────────────────────────────────────────────────────────

  private val captal: Command[CaptalCommand] =
    Command("captal", Options.none, Args.none)
      .subcommands(shared, location, video)

  val cliApp: CliApp[Any, Any, CaptalCommand] = CliApp.make(
    name = "captal",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Whitelabel captive portal provisioning CLI"),
    command = captal,
    config = CliConfig.default.copy(finalCheckBuiltIn = false)
  ) { cmd =>
    val program: ZIO[Any, Any, CaptalCommand] = (cmd match
      case CaptalCommand.SharedInit =>
        SharedInitCommand.run

      case CaptalCommand.SharedPush =>
        SharedPushCommand.run
          .provide(CaptalConfig.layer, AwsLayers.ecs)

      case CaptalCommand.LocationInit(slug) =>
        InitCommand.run(slug)

      case CaptalCommand.LocationPush(slug) =>
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
