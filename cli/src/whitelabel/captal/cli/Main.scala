package whitelabel.captal.cli

import zio.*

object Main extends ZIOAppDefault:

  private val KebabCase = "[a-z0-9-]+".r

  private def validateSlug(slug: String): IO[CliError, String] =
    if slug.isEmpty then ZIO.fail(CliError.InvalidSlug("slug cannot be empty"))
    else if !KebabCase.matches(slug) then
      ZIO.fail(CliError.InvalidSlug(s"slug must be kebab-case (a-z, 0-9, hyphens): $slug"))
    else ZIO.succeed(slug)

  override val run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args <- ZIOAppArgs.getArgs
      _ <- dispatch(args.toList)
    yield ()

  private def dispatch(args: List[String]): ZIO[Any, Any, Unit] =
    val program = args match
      case "init" :: slug :: _ =>
        validateSlug(slug).flatMap(InitCommand.run)

      case "push" :: slug :: _ =>
        validateSlug(slug).flatMap: s =>
          PushCommand
            .run(s)
            .provide(PushConfig.layer, AwsLayers.s3, AwsLayers.ecr, AwsLayers.ecs, AwsLayers.elbv2)

      case "video" :: slug :: "--promo" :: videoPath :: _ =>
        validateSlug(slug).flatMap: s =>
          VideoCommand.runPromo(s, videoPath).provide(AwsLayers.s3)

      case "video" :: slug :: advertiserSlug :: videoPath :: _ =>
        (validateSlug(slug) <&> validateSlug(advertiserSlug)).flatMap: (s, a) =>
          VideoCommand.run(s, a, videoPath).provide(AwsLayers.s3)

      case _ =>
        Console.printLine:
          """captal - Whitelabel provisioning CLI
            |
            |Usage:
            |  captal init <slug>              Create a new location project at /etc/captal/
            |  captal push <slug>              Deploy location to AWS (S3 + ECR + ECS + ALB)
            |  captal video <slug> <adv> <file>  Upload video to S3 and create YAML placeholder
            |  captal video <slug> --promo <file> Upload promo video to S3 and create YAML""".stripMargin

    program.tapError:
      case e: CliError => Console.printLineError(s"Error: ${e.message}")
      case e           => Console.printLineError(s"Unexpected error: $e")
