package whitelabel.captal.cli

import java.nio.file.{Files, Paths}

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.yaml.parser as yamlParser
import zio.*

/** Central configuration for the CLI, read from ./shared/captal.yaml. AWS credentials are optional
  * — if absent, the SDK default provider chain is used.
  */
final case class CaptalConfig(
    aws: CaptalConfig.Aws,
    images: CaptalConfig.Images,
    s3: CaptalConfig.S3,
    ecs: CaptalConfig.Ecs,
    alb: CaptalConfig.Alb,
    cloudfront: CaptalConfig.CloudFront,
    database: CaptalConfig.Database,
    server: CaptalConfig.Server = CaptalConfig.Server())

object CaptalConfig:
  final case class Aws(
      region: String = "us-east-1",
      accessKeyId: Option[String] = None,
      secretAccessKey: Option[String] = None,
      sessionToken: Option[String] = None)
  object Aws:
    given Decoder[Aws] = deriveDecoder

  final case class Images(
      api: String,
      provision: String,
      shared: String,
      locations: String)
  object Images:
    given Decoder[Images] = deriveDecoder

  final case class S3(bucket: String, bundlePrefix: String = "bundle/")
  object S3:
    given Decoder[S3] = deriveDecoder

  final case class CloudFront(distributionId: String)
  object CloudFront:
    given Decoder[CloudFront] = deriveDecoder

  final case class Ecs(
      cluster: String,
      cpu: String = "256",
      memory: String = "512",
      desiredCount: Int = 1,
      subnets: List[String],
      securityGroups: List[String],
      executionRoleArn: String,
      taskRoleArn: Option[String] = None)
  object Ecs:
    given Decoder[Ecs] = deriveDecoder

  final case class Alb(
      listenerArn: String,
      vpcId: String,
      domain: String = "captal.app",
      healthCheckPath: String = "/health")
  object Alb:
    given Decoder[Alb] = deriveDecoder

  final case class Database(url: String)
  object Database:
    given Decoder[Database] = deriveDecoder

  final case class Server(devMode: Boolean = false, devEndpoints: Boolean = false)
  object Server:
    given Decoder[Server] = deriveDecoder

  given Decoder[CaptalConfig] = deriveDecoder

  private val ConfigPath = Paths.get("shared/captal.yaml")

  val layer: ZLayer[Any, CliError, CaptalConfig] = ZLayer.fromZIO:
    ZIO
      .attempt:
        if !Files.exists(ConfigPath) then
          throw new RuntimeException(
            s"Config file not found: $ConfigPath. Run 'captal shared init' first.")
        val content = Files.readString(ConfigPath)
        yamlParser
          .parse(content)
          .flatMap(_.as[CaptalConfig])
          .fold(
            e => throw new RuntimeException(s"Failed to parse $ConfigPath: ${e.getMessage}"),
            identity)
      .mapError:
        case e: CliError =>
          e
        case e =>
          CliError.ConfigError(e.getMessage)
end CaptalConfig
