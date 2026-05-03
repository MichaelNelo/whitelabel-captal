package whitelabel.captal.cli.commands

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path, Paths}
import java.util.zip.GZIPOutputStream

import io.circe.yaml.parser as yamlParser
import whitelabel.captal.cli.docker.DockerImageBuilder
import whitelabel.captal.cli.{CaptalConfig, CliError, Output}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.cloudfront.CloudFrontClient
import software.amazon.awssdk.services.cloudfront.model.{
  CreateInvalidationRequest,
  InvalidationBatch,
  Paths as CfPaths
}
import software.amazon.awssdk.services.ecr.EcrClient
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
  ModifyRuleRequest,
  PathPatternConditionConfig,
  RuleCondition,
  TargetGroupNotFoundException,
  TargetTypeEnum
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  CopyObjectRequest,
  ListObjectsV2Request,
  PutObjectRequest
}
import whitelabel.captal.infra.provision.LocationYaml
import zio.*

/** Deploys a location to AWS: S3 assets + per-location image + ECS service + ALB rule + CDN
  * invalidation.
  */
object PushCommand:

  private val LocationsDir = Paths.get("locations")

  type Env =
    CaptalConfig & S3Client & EcsClient & ElasticLoadBalancingV2Client & EcrClient &
      CloudFrontClient

  def run(slug: String): ZIO[Env, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      locationDir = LocationsDir.resolve(slug)
      locationYaml <- readLocationYaml(locationDir)
      desiredCount = locationYaml.desiredCount.getOrElse(config.ecs.desiredCount)
      tag          = s"$slug-${timestampTag()}"

      _ <- Output.header(s"Deploying location '$slug'")

      _ <- Output.step(1, 5, "Syncing assets to S3...")
      _ <- uploadAssets(slug)

      _ <- Output.step(2, 5, "Building location image...")
      imageUri <- DockerImageBuilder.buildAndPush(
        base = config.images.api,
        repo = config.images.locations,
        tag = tag,
        contextDir = locationDir,
        dockerfileResource = "templates/dockerfiles/Dockerfile.locations")

      _          <- Output.step(3, 5, "Updating ECS service...")
      taskDefArn <- registerTaskDefinition(slug, imageUri)
      _          <- createOrUpdateService(slug, taskDefArn, desiredCount)

      _ <- Output.step(4, 5, "Configuring ALB rule...")
      _ <- configureAlbRule(slug)

      _ <- Output.step(5, 5, "Invalidating CloudFront...")
      _ <- invalidateCdn(slug)

      _ <- Output.success(s"Deployment complete for '$slug'")
    yield ()

  private def timestampTag(): String =
    val fmt = java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss")
      .withZone(java.time.ZoneOffset.UTC)
    fmt.format(java.time.Instant.now())

  // ─────────────────────────────────────────────────────────────────────────────
  // Location YAML
  // ─────────────────────────────────────────────────────────────────────────────

  private def readLocationYaml(locationDir: Path): IO[CliError, LocationYaml] = ZIO
    .attempt:
      val path = locationDir.resolve("location.yaml")
      val content = Files.readString(path)
      yamlParser
        .parse(content)
        .flatMap(_.as[LocationYaml])
        .fold(
          e => throw new RuntimeException(s"Failed to parse location.yaml: ${e.getMessage}"),
          identity)
    .mapError(e => CliError.ConfigError(e.getMessage))

  // ─────────────────────────────────────────────────────────────────────────────
  // S3: Sync client bundle (server-side copy from master) + per-location custom assets
  // ─────────────────────────────────────────────────────────────────────────────

  private def uploadAssets(slug: String): ZIO[CaptalConfig & S3Client, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      _      <- copyBundleFromMaster(slug, config.s3.bucket, config.s3.bundlePrefix)
      _      <- uploadCustomAssets(slug, config.s3.bucket)
      _      <- Output.detail(s"Synced assets to s3://${config.s3.bucket}/$slug/")
    yield ()

  /** Server-side copy of all objects under `<bundlePrefix>` to `<slug>/`. Idempotent. */
  private def copyBundleFromMaster(
      slug: String,
      bucket: String,
      bundlePrefix: String): ZIO[S3Client, CliError, Unit] =
    aws("S3 copy bundle"):
      ZIO.serviceWithZIO[S3Client]: s3 =>
        ZIO.attemptBlocking:
          val normalizedPrefix =
            if bundlePrefix.endsWith("/") then bundlePrefix else s"$bundlePrefix/"
          val response = s3.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucket).prefix(normalizedPrefix).build())
          val keys = response
            .contents()
            .stream()
            .map(_.key())
            .toArray
            .toList
            .collect { case k: String => k }
          if keys.isEmpty then
            throw new RuntimeException(
              s"No bundle found at s3://$bucket/$normalizedPrefix — push the bundle from the project release flow first")
          keys.foreach { srcKey =>
            val relative = srcKey.stripPrefix(normalizedPrefix)
            val destKey = s"$slug/$relative"
            s3.copyObject(
              CopyObjectRequest
                .builder()
                .sourceBucket(bucket)
                .sourceKey(srcKey)
                .destinationBucket(bucket)
                .destinationKey(destKey)
                .build())
          }

  private def uploadCustomAssets(slug: String, bucket: String): ZIO[S3Client, CliError, Unit] =
    val assetsDir = LocationsDir.resolve(slug).resolve("assets")
    ZIO
      .when(Files.exists(assetsDir)):
        val styles = assetsDir.resolve("styles.css")
        val icon = assetsDir.resolve("brand-icon.svg")
        val uploadStyles =
          ZIO.when(Files.exists(styles)):
            putObjectBytes(
              bucket,
              s"$slug/custom-styles.css.gz",
              gzipBytes(Files.readAllBytes(styles)),
              "text/css",
              Some("gzip"))
        val uploadIcon =
          ZIO.when(Files.exists(icon)):
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
      slug: String,
      imageUri: String): ZIO[CaptalConfig & EcsClient, CliError, String] =
    for
      config <- ZIO.service[CaptalConfig]
      arn <-
        aws("ECS registerTaskDefinition"):
          ZIO.serviceWithZIO[EcsClient]: ecs =>
            ZIO.attemptBlocking:
              val family = s"captal-$slug"
              val container = ContainerDefinition
                .builder()
                .name("captal")
                .image(imageUri)
                .essential(true)
                .portMappings(PortMapping.builder().containerPort(8080).build())
                .environment(
                  KeyValuePair.builder().name("LOCATION_SLUG").value(slug).build(),
                  KeyValuePair
                    .builder()
                    .name("PROVISION_DIR")
                    .value("/etc/captal/provision")
                    .build(),
                  KeyValuePair.builder().name("DB_URL").value(config.database.url).build(),
                  KeyValuePair
                    .builder()
                    .name("SERVER_DEV_MODE")
                    .value(config.server.devMode.toString)
                    .build(),
                  KeyValuePair
                    .builder()
                    .name("SERVER_DEV_ENDPOINTS")
                    .value(config.server.devEndpoints.toString)
                    .build()
                )
                .logConfiguration(
                  LogConfiguration
                    .builder()
                    .logDriver(LogDriver.AWSLOGS)
                    .options(
                      java
                        .util
                        .Map
                        .of(
                          "awslogs-group",
                          s"/ecs/captal-$slug",
                          "awslogs-region",
                          config.aws.region,
                          "awslogs-stream-prefix",
                          "ecs"))
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
      _ <- Output.detail(s"Registered task definition: $arn")
    yield arn

  private def networkConfig(config: CaptalConfig): NetworkConfiguration = NetworkConfiguration
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
      isActive <-
        aws("ECS describeServices"):
          ZIO.serviceWithZIO[EcsClient]: ecs =>
            ZIO.attemptBlocking:
              val resp = ecs.describeServices(
                DescribeServicesRequest
                  .builder()
                  .cluster(config.ecs.cluster)
                  .services(serviceName)
                  .build())
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
          *> Output.detail(s"Updated service: $serviceName (replicas: $desiredCount)")
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
          *> Output.detail(s"Created service: $serviceName (replicas: $desiredCount)")
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // ALB: Listener rule for routing
  // ─────────────────────────────────────────────────────────────────────────────

  private def configureAlbRule(
      slug: String): ZIO[CaptalConfig & ElasticLoadBalancingV2Client, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      tgName = s"captal-$slug"
      tgArn <- ensureTargetGroup(tgName, slug, config)
      _     <- upsertAlbRule(slug, tgArn, config)
    yield ()

  private def ensureTargetGroup(
      name: String,
      slug: String,
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
                      .healthCheckPath(s"/$slug/api/health")
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
          val pathPattern = s"/$slug/api/*"
          val forwardAction = Action
            .builder()
            .`type`(ActionTypeEnum.FORWARD)
            .targetGroupArn(tgArn)
            .build()
          val rules = elbv2.describeRules(
            DescribeRulesRequest.builder().listenerArn(config.alb.listenerArn).build())

          val existingRule = rules
            .rules()
            .stream()
            .filter: rule =>
              rule
                .conditions()
                .stream()
                .anyMatch: c =>
                  c.pathPatternConfig() != null &&
                    c.pathPatternConfig().values().contains(pathPattern)
            .findFirst()

          if existingRule.isPresent then
            elbv2.modifyRule(
              ModifyRuleRequest
                .builder()
                .ruleArn(existingRule.get().ruleArn())
                .actions(forwardAction)
                .build())
            java.lang.System.out.println(s"  Updated ALB rule for $pathPattern -> captal-$slug")
          else
            val usedPriorities =
              rules
                .rules()
                .stream()
                .map(_.priority())
                .filter(_ != "default")
                .map(_.toInt)
                .toArray
                .map(_.asInstanceOf[Int])
                .toSet
            val priority = (1 to 50000).find(!usedPriorities.contains(_)).getOrElse(1)

            elbv2.createRule(
              CreateRuleRequest
                .builder()
                .listenerArn(config.alb.listenerArn)
                .conditions(
                  RuleCondition
                    .builder()
                    .pathPatternConfig(
                      PathPatternConditionConfig.builder().values(pathPattern).build())
                    .build())
                .actions(forwardAction)
                .priority(priority)
                .build())
            java.lang.System.out.println(s"  Created ALB rule for $pathPattern -> captal-$slug")
          end if

  // ─────────────────────────────────────────────────────────────────────────────
  // CloudFront: invalidate per-location paths
  // ─────────────────────────────────────────────────────────────────────────────

  private def invalidateCdn(slug: String): ZIO[CaptalConfig & CloudFrontClient, CliError, Unit] =
    aws("CloudFront createInvalidation"):
      ZIO.serviceWithZIO[CloudFrontClient]: cf =>
        ZIO.serviceWithZIO[CaptalConfig]: config =>
          ZIO.attemptBlocking:
            cf.createInvalidation(
              CreateInvalidationRequest
                .builder()
                .distributionId(config.cloudfront.distributionId)
                .invalidationBatch(
                  InvalidationBatch
                    .builder()
                    .callerReference(java.time.Instant.now().toString)
                    .paths(CfPaths.builder().items(s"/$slug/*").quantity(1).build())
                    .build())
                .build())
    .flatMap: _ =>
      Output.detail(s"CloudFront invalidation requested for /$slug/*")

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private def aws[R, A](operation: String)(effect: ZIO[R, Throwable, A]): ZIO[R, CliError, A] =
    effect.mapError(CliError.AwsError(operation, _))

  private def gzipBytes(data: Array[Byte]): Array[Byte] =
    val bos = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(bos)
    gzip.write(data)
    gzip.close()
    bos.toByteArray
end PushCommand
