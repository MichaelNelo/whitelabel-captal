package whitelabel.captal.cli

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path, Paths}
import java.util.zip.GZIPOutputStream

import io.circe.yaml.parser as yamlParser
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{
  AssignPublicIp,
  AwsVpcConfiguration,
  Compatibility,
  ContainerDefinition,
  CreateServiceRequest,
  DescribeServicesRequest,
  KeyValuePair,
  LogConfiguration,
  LogDriver,
  NetworkConfiguration,
  NetworkMode,
  PortMapping,
  RegisterTaskDefinitionRequest,
  UpdateServiceRequest
}
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  Action,
  ActionTypeEnum,
  CreateRuleRequest,
  CreateTargetGroupRequest,
  DescribeRulesRequest,
  DescribeTargetGroupsRequest,
  HostHeaderConditionConfig,
  ModifyRuleRequest,
  RuleCondition,
  TargetGroupNotFoundException,
  TargetTypeEnum
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import whitelabel.captal.infra.provision.LocationYaml
import zio.*

/** Deploys a location to AWS: S3 assets + ECS service + ALB rule. */
object PushCommand:

  private val LocationsDir = Paths.get("/etc/captal/locations")

  type Env = CaptalConfig & S3Client & EcsClient & ElasticLoadBalancingV2Client

  def run(slug: String): ZIO[Env, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      locationDir = LocationsDir.resolve(slug)
      locationYaml <- readLocationYaml(locationDir)
      desiredCount = locationYaml.desiredCount.getOrElse(config.ecs.desiredCount)

      _ <- Console.printLine(s"Deploying location '$slug' to AWS...").orDie

      _ <- Console.printLine("[1/3] Uploading assets to S3...").orDie
      _ <- uploadAssets(slug)

      _ <- Console.printLine("[2/3] Updating ECS service...").orDie
      taskDefArn <- registerTaskDefinition(slug)
      _          <- createOrUpdateService(slug, taskDefArn, desiredCount)

      _ <- Console.printLine("[3/3] Configuring ALB rule...").orDie
      _ <- configureAlbRule(slug)

      _ <- Console.printLine(s"Deployment complete for '$slug'").orDie
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // Location YAML
  // ─────────────────────────────────────────────────────────────────────────────

  private def readLocationYaml(locationDir: Path): IO[CliError, LocationYaml] =
    ZIO
      .attempt:
        val path = locationDir.resolve("location.yaml")
        val content = Files.readString(path)
        yamlParser
          .parse(content)
          .flatMap(_.as[LocationYaml])
          .fold(e => throw new RuntimeException(s"Failed to parse location.yaml: ${e.getMessage}"), identity)
      .mapError(e => CliError.ConfigError(e.getMessage))

  // ─────────────────────────────────────────────────────────────────────────────
  // S3: Upload client bundle assets
  // ─────────────────────────────────────────────────────────────────────────────

  private def uploadAssets(slug: String): ZIO[CaptalConfig & S3Client, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      _      <- execProcess("./mill", "client.bundle")(CliError.BuildFailed("client bundle"))
      _      <- uploadBundleFiles(slug, config.s3.bucket)
      _      <- uploadCustomAssets(slug, config.s3.bucket)
      _      <- Console.printLine(s"  Uploaded assets to s3://${config.s3.bucket}/$slug/").orDie
    yield ()

  private def uploadBundleFiles(slug: String, bucket: String): ZIO[S3Client, CliError, Unit] =
    val bundleDir = Paths.get("out/client/bundle.dest")
    val files = List(
      ("index.html", "text/html", false),
      ("main.js.gz", "application/javascript", true),
      ("styles.css.gz", "text/css", true))
    ZIO.foreachDiscard(files) { (name, contentType, gzipped) =>
      val file = bundleDir.resolve(name)
      ZIO.when(Files.exists(file)):
        val encoding = if gzipped then Some("gzip") else None
        putObject(bucket, s"$slug/$name", file, contentType, encoding)
    }

  private def uploadCustomAssets(slug: String, bucket: String): ZIO[S3Client, CliError, Unit] =
    val assetsDir = LocationsDir.resolve("assets")
    ZIO.when(Files.exists(assetsDir)):
      val styles = assetsDir.resolve("styles.css")
      val icon = assetsDir.resolve("brand-icon.svg")
      val uploadStyles = ZIO.when(Files.exists(styles)):
        putObjectBytes(bucket, s"$slug/custom-styles.css.gz", gzipBytes(Files.readAllBytes(styles)), "text/css", Some("gzip"))
      val uploadIcon = ZIO.when(Files.exists(icon)):
        putObject(bucket, s"$slug/brand-icon.svg", icon, "image/svg+xml", None)
      uploadStyles *> uploadIcon
    .unit

  private def putObject(
      bucket: String,
      key: String,
      file: Path,
      contentType: String,
      encoding: Option[String]): ZIO[S3Client, CliError, Unit] =
    aws("S3 putObject"):
      ZIO.serviceWithZIO[S3Client]: s3 =>
        ZIO.attemptBlocking:
          val builder = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
          encoding.foreach(builder.contentEncoding)
          s3.putObject(builder.build(), file)

  private def putObjectBytes(
      bucket: String,
      key: String,
      data: Array[Byte],
      contentType: String,
      encoding: Option[String]): ZIO[S3Client, CliError, Unit] =
    aws("S3 putObject"):
      ZIO.serviceWithZIO[S3Client]: s3 =>
        ZIO.attemptBlocking:
          val builder = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
          encoding.foreach(builder.contentEncoding)
          s3.putObject(builder.build(), RequestBody.fromBytes(data))

  // ─────────────────────────────────────────────────────────────────────────────
  // ECS: Task definition and service
  // ─────────────────────────────────────────────────────────────────────────────

  private def registerTaskDefinition(
      slug: String): ZIO[CaptalConfig & EcsClient, CliError, String] =
    for
      config <- ZIO.service[CaptalConfig]
      arn <- aws("ECS registerTaskDefinition"):
        ZIO.serviceWithZIO[EcsClient]: ecs =>
          ZIO.attemptBlocking:
            val family = s"captal-$slug"
            val container = ContainerDefinition
              .builder()
              .name("captal")
              .image(config.image)
              .essential(true)
              .portMappings(PortMapping.builder().containerPort(8080).build())
              .environment(
                KeyValuePair.builder().name("LOCATION_SLUG").value(slug).build(),
                KeyValuePair.builder().name("PROVISION_DIR").value(s"/etc/captal/locations/$slug").build(),
                KeyValuePair.builder().name("SHARED_DIR").value("/etc/captal/shared").build(),
                KeyValuePair.builder().name("DB_URL").value(config.database.url).build(),
                KeyValuePair.builder().name("SERVER_DEV_MODE").value(config.server.devMode.toString).build(),
                KeyValuePair.builder().name("SERVER_DEV_ENDPOINTS").value(config.server.devEndpoints.toString).build())
              .logConfiguration(
                LogConfiguration
                  .builder()
                  .logDriver(LogDriver.AWSLOGS)
                  .options(java.util.Map.of(
                    "awslogs-group", s"/ecs/captal-$slug",
                    "awslogs-region", config.aws.region,
                    "awslogs-stream-prefix", "ecs"))
                  .build())
              .build()

            val builder = RegisterTaskDefinitionRequest
              .builder()
              .family(family)
              .networkMode(NetworkMode.AWSVPC)
              .requiresCompatibilities(Compatibility.FARGATE)
              .cpu(config.ecs.cpu)
              .memory(config.ecs.memory)
              .containerDefinitions(container)
              .executionRoleArn(config.ecs.executionRoleArn)
            config.ecs.taskRoleArn.foreach(builder.taskRoleArn)

            ecs.registerTaskDefinition(builder.build()).taskDefinition().taskDefinitionArn()
      _ <- Console.printLine(s"  Registered task definition: $arn").orDie
    yield arn

  private def networkConfig(config: CaptalConfig): NetworkConfiguration =
    NetworkConfiguration
      .builder()
      .awsvpcConfiguration(
        AwsVpcConfiguration
          .builder()
          .subnets(config.ecs.subnets*)
          .securityGroups(config.ecs.securityGroups*)
          .assignPublicIp(AssignPublicIp.ENABLED)
          .build())
      .build()

  private def createOrUpdateService(
      slug: String,
      taskDefArn: String,
      desiredCount: Int): ZIO[CaptalConfig & EcsClient, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      serviceName = s"captal-$slug"
      isActive <- aws("ECS describeServices"):
        ZIO.serviceWithZIO[EcsClient]: ecs =>
          ZIO.attemptBlocking:
            val resp = ecs.describeServices(
              DescribeServicesRequest.builder().cluster(config.ecs.cluster).services(serviceName).build())
            resp.services().stream().filter(_.status() == "ACTIVE").count() > 0
      _ <-
        if isActive then
          aws("ECS updateService"):
            ZIO.serviceWithZIO[EcsClient]: ecs =>
              ZIO.attemptBlocking:
                ecs.updateService(
                  UpdateServiceRequest
                    .builder()
                    .cluster(config.ecs.cluster)
                    .service(serviceName)
                    .taskDefinition(taskDefArn)
                    .desiredCount(desiredCount)
                    .networkConfiguration(networkConfig(config))
                    .forceNewDeployment(true)
                    .build())
          *> Console.printLine(s"  Updated service: $serviceName (replicas: $desiredCount)").orDie
        else
          aws("ECS createService"):
            ZIO.serviceWithZIO[EcsClient]: ecs =>
              ZIO.attemptBlocking:
                ecs.createService(
                  CreateServiceRequest
                    .builder()
                    .cluster(config.ecs.cluster)
                    .serviceName(serviceName)
                    .taskDefinition(taskDefArn)
                    .desiredCount(desiredCount)
                    .launchType("FARGATE")
                    .networkConfiguration(networkConfig(config))
                    .build())
          *> Console.printLine(s"  Created service: $serviceName (replicas: $desiredCount)").orDie
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // ALB: Listener rule for routing
  // ─────────────────────────────────────────────────────────────────────────────

  private def configureAlbRule(
      slug: String): ZIO[CaptalConfig & ElasticLoadBalancingV2Client, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      tgName = s"captal-$slug"
      tgArn <- ensureTargetGroup(tgName, config)
      _     <- upsertAlbRule(slug, tgArn, config)
    yield ()

  private def ensureTargetGroup(
      name: String,
      config: CaptalConfig): ZIO[ElasticLoadBalancingV2Client, CliError, String] =
    aws("ELBv2 describeTargetGroups"):
      ZIO.serviceWithZIO[ElasticLoadBalancingV2Client]: elbv2 =>
        ZIO
          .attemptBlocking:
            elbv2
              .describeTargetGroups(DescribeTargetGroupsRequest.builder().names(name).build())
              .targetGroups()
              .get(0)
              .targetGroupArn()
          .catchSome:
            case _: TargetGroupNotFoundException =>
              ZIO.attemptBlocking:
                elbv2
                  .createTargetGroup(
                    CreateTargetGroupRequest
                      .builder()
                      .name(name)
                      .protocol("HTTP")
                      .port(8080)
                      .vpcId(config.alb.vpcId)
                      .targetType(TargetTypeEnum.IP)
                      .healthCheckPath(config.alb.healthCheckPath)
                      .build())
                  .targetGroups()
                  .get(0)
                  .targetGroupArn()

  private def upsertAlbRule(
      slug: String,
      tgArn: String,
      config: CaptalConfig): ZIO[ElasticLoadBalancingV2Client, CliError, Unit] =
    aws("ELBv2 rules"):
      ZIO.serviceWithZIO[ElasticLoadBalancingV2Client]: elbv2 =>
        ZIO.attemptBlocking:
          val hostPattern = s"$slug.${config.alb.domain}"
          val forwardAction = Action.builder().`type`(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build()
          val rules = elbv2.describeRules(
            DescribeRulesRequest.builder().listenerArn(config.alb.listenerArn).build())

          val existingRule = rules
            .rules()
            .stream()
            .filter: rule =>
              rule.conditions().stream().anyMatch: c =>
                c.hostHeaderConfig() != null && c.hostHeaderConfig().values().contains(hostPattern)
            .findFirst()

          if existingRule.isPresent then
            elbv2.modifyRule(
              ModifyRuleRequest
                .builder()
                .ruleArn(existingRule.get().ruleArn())
                .actions(forwardAction)
                .build())
            println(s"  Updated ALB rule for $hostPattern -> captal-$slug")
          else
            val usedPriorities = rules
              .rules().stream().map(_.priority()).filter(_ != "default")
              .map(_.toInt).toArray.map(_.asInstanceOf[Int]).toSet
            val priority = (1 to 50000).find(!usedPriorities.contains(_)).getOrElse(1)

            elbv2.createRule(
              CreateRuleRequest
                .builder()
                .listenerArn(config.alb.listenerArn)
                .conditions(
                  RuleCondition
                    .builder()
                    .hostHeaderConfig(
                      HostHeaderConditionConfig.builder().values(hostPattern).build())
                    .build())
                .actions(forwardAction)
                .priority(priority)
                .build())
            println(s"  Created ALB rule for $hostPattern -> captal-$slug")

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private def aws[R, A](operation: String)(effect: ZIO[R, Throwable, A]): ZIO[R, CliError, A] =
    effect.mapError(CliError.AwsError(operation, _))

  private def execProcess(cmd: String*)(onFail: => CliError): IO[CliError, Unit] =
    ZIO
      .attemptBlocking:
        val exitCode = new ProcessBuilder(cmd*).inheritIO().start().waitFor()
        if exitCode != 0 then throw new RuntimeException(s"exit code $exitCode")
      .mapError(_ => onFail)
      .unit

  private def gzipBytes(data: Array[Byte]): Array[Byte] =
    val bos = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(bos)
    gzip.write(data)
    gzip.close()
    bos.toByteArray
