package whitelabel.captal.cli

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path, Paths}
import java.util.Base64
import java.util.zip.GZIPOutputStream

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.ecr.model.{
  CreateRepositoryRequest,
  DescribeRepositoriesRequest,
  GetAuthorizationTokenRequest,
  RepositoryNotFoundException
}
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{
  Compatibility,
  ContainerDefinition,
  CreateServiceRequest,
  DescribeServicesRequest,
  KeyValuePair,
  LogConfiguration,
  LogDriver,
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
import zio.*

/** Deploys a location to AWS: S3 assets + ECR image + ECS service + ALB rule. */
object PushCommand:

  private val BaseDir = Paths.get("/etc/captal")

  type Env = PushConfig & S3Client & EcrClient & EcsClient & ElasticLoadBalancingV2Client

  def run(slug: String): ZIO[Env, CliError, Unit] =
    for
      _ <- Console.printLine(s"Deploying location '$slug' to AWS...").orDie

      _ <- Console.printLine("[1/4] Uploading assets to S3...").orDie
      _ <- uploadAssets(slug)

      _ <- Console.printLine("[2/4] Building and pushing Docker image...").orDie
      imageUri <- buildAndPushImage(slug)

      _ <- Console.printLine("[3/4] Updating ECS service...").orDie
      taskDefArn <- registerTaskDefinition(slug, imageUri)
      _          <- createOrUpdateService(slug, taskDefArn)

      _ <- Console.printLine("[4/4] Configuring ALB rule...").orDie
      _ <- configureAlbRule(slug)

      _ <- Console.printLine(s"Deployment complete for '$slug'").orDie
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // S3: Upload client bundle assets
  // ─────────────────────────────────────────────────────────────────────────────

  private def uploadAssets(slug: String): ZIO[PushConfig & S3Client, CliError, Unit] =
    for
      config <- ZIO.service[PushConfig]
      _      <- execProcess("./mill", "client.bundle")(CliError.BuildFailed("client bundle"))
      _      <- uploadBundleFiles(slug, config)
      _      <- uploadCustomAssets(slug, config)
      _      <- Console.printLine(s"  Uploaded assets to s3://${config.s3Bucket}/$slug/").orDie
    yield ()

  private def uploadBundleFiles(slug: String, config: PushConfig): ZIO[S3Client, CliError, Unit] =
    val bundleDir = Paths.get("out/client/bundle.dest")
    val files = List(
      ("index.html", "text/html", false),
      ("main.js.gz", "application/javascript", true),
      ("styles.css.gz", "text/css", true))
    ZIO.foreachDiscard(files): (name, contentType, gzipped) =>
      val file = bundleDir.resolve(name)
      ZIO.when(Files.exists(file)):
        val encoding = if gzipped then Some("gzip") else None
        putObject(config.s3Bucket, s"$slug/$name", file, contentType, encoding)

  private def uploadCustomAssets(slug: String, config: PushConfig): ZIO[S3Client, CliError, Unit] =
    val assetsDir = BaseDir.resolve("assets")
    ZIO.when(Files.exists(assetsDir)):
      val styles = assetsDir.resolve("styles.css")
      val icon = assetsDir.resolve("brand-icon.svg")
      val uploadStyles = ZIO.when(Files.exists(styles)):
        putObjectBytes(config.s3Bucket, s"$slug/custom-styles.css.gz", gzipBytes(Files.readAllBytes(styles)), "text/css", Some("gzip"))
      val uploadIcon = ZIO.when(Files.exists(icon)):
        putObject(config.s3Bucket, s"$slug/brand-icon.svg", icon, "image/svg+xml", None)
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
  // ECR: Build and push Docker image
  // ─────────────────────────────────────────────────────────────────────────────

  private def buildAndPushImage(slug: String): ZIO[PushConfig & EcrClient, CliError, String] =
    for
      config <- ZIO.service[PushConfig]
      _      <- ensureEcrRepo(config.ecrRepo)
      auth   <- getEcrAuth
      imageTag = s"${auth.registry}/${config.ecrRepo}:$slug-${java.lang.System.currentTimeMillis() / 1000}"
      _ <- execProcess("docker", "build", "-f", "Dockerfile", "-t", imageTag, ".")(
        CliError.DockerError("build"))
      _ <- dockerLogin(auth.registry, auth.password)
      _ <- execProcess("docker", "push", imageTag)(CliError.DockerError("push"))
      _ <- Console.printLine(s"  Pushed image: $imageTag").orDie
    yield imageTag

  private case class EcrAuth(registry: String, password: String)

  private def ensureEcrRepo(repoName: String): ZIO[EcrClient, CliError, Unit] =
    aws("ECR describeRepositories"):
      ZIO.serviceWithZIO[EcrClient]: ecr =>
        ZIO
          .attemptBlocking(ecr.describeRepositories(
            DescribeRepositoriesRequest.builder().repositoryNames(repoName).build()))
          .catchSome:
            case _: RepositoryNotFoundException =>
              ZIO.attemptBlocking(
                ecr.createRepository(CreateRepositoryRequest.builder().repositoryName(repoName).build()))
    .unit

  private def getEcrAuth: ZIO[EcrClient, CliError, EcrAuth] =
    aws("ECR getAuthorizationToken"):
      ZIO.serviceWithZIO[EcrClient]: ecr =>
        ZIO.attemptBlocking:
          val resp = ecr.getAuthorizationToken(GetAuthorizationTokenRequest.builder().build())
          val data = resp.authorizationData().get(0)
          val registry = data.proxyEndpoint().stripPrefix("https://")
          val decoded = new String(Base64.getDecoder.decode(data.authorizationToken()))
          val password = decoded.split(":")(1)
          EcrAuth(registry, password)

  private def dockerLogin(registry: String, password: String): IO[CliError, Unit] =
    ZIO
      .attemptBlocking:
        val proc = new ProcessBuilder("docker", "login", "--username", "AWS", "--password-stdin", registry)
          .redirectErrorStream(true)
          .start()
        proc.getOutputStream.write(password.getBytes)
        proc.getOutputStream.close()
        val exitCode = proc.waitFor()
        if exitCode != 0 then throw new RuntimeException(s"exit code $exitCode")
      .mapError(_ => CliError.DockerError("login"))
      .unit

  // ─────────────────────────────────────────────────────────────────────────────
  // ECS: Task definition and service
  // ─────────────────────────────────────────────────────────────────────────────

  private def registerTaskDefinition(
      slug: String,
      imageUri: String): ZIO[PushConfig & EcsClient, CliError, String] =
    for
      config <- ZIO.service[PushConfig]
      arn <- aws("ECS registerTaskDefinition"):
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
                KeyValuePair.builder().name("PROVISION_DIR").value("/etc/captal").build(),
                KeyValuePair.builder().name("DB_URL").value(config.dbUrl).build(),
                KeyValuePair.builder().name("SERVER_DEV_MODE").value("false").build(),
                KeyValuePair.builder().name("SERVER_DEV_ENDPOINTS").value("false").build())
              .logConfiguration(
                LogConfiguration
                  .builder()
                  .logDriver(LogDriver.AWSLOGS)
                  .options(java.util.Map.of(
                    "awslogs-group", s"/ecs/captal-$slug",
                    "awslogs-region", ecs.serviceClientConfiguration().region().id(),
                    "awslogs-stream-prefix", "ecs"))
                  .build())
              .build()

            val builder = RegisterTaskDefinitionRequest
              .builder()
              .family(family)
              .networkMode(NetworkMode.AWSVPC)
              .requiresCompatibilities(Compatibility.FARGATE)
              .cpu("256")
              .memory("512")
              .containerDefinitions(container)
              .executionRoleArn(config.executionRoleArn)
            config.taskRoleArn.foreach(builder.taskRoleArn)

            ecs.registerTaskDefinition(builder.build()).taskDefinition().taskDefinitionArn()
      _ <- Console.printLine(s"  Registered task definition: $arn").orDie
    yield arn

  private def createOrUpdateService(
      slug: String,
      taskDefArn: String): ZIO[PushConfig & EcsClient, CliError, Unit] =
    for
      config <- ZIO.service[PushConfig]
      serviceName = s"captal-$slug"
      isActive <- aws("ECS describeServices"):
        ZIO.serviceWithZIO[EcsClient]: ecs =>
          ZIO.attemptBlocking:
            val resp = ecs.describeServices(
              DescribeServicesRequest.builder().cluster(config.ecsCluster).services(serviceName).build())
            resp.services().stream().filter(_.status() == "ACTIVE").count() > 0
      _ <-
        if isActive then
          aws("ECS updateService"):
            ZIO.serviceWithZIO[EcsClient]: ecs =>
              ZIO.attemptBlocking:
                ecs.updateService(
                  UpdateServiceRequest
                    .builder()
                    .cluster(config.ecsCluster)
                    .service(serviceName)
                    .taskDefinition(taskDefArn)
                    .forceNewDeployment(true)
                    .build())
          *> Console.printLine(s"  Updated service: $serviceName").orDie
        else
          aws("ECS createService"):
            ZIO.serviceWithZIO[EcsClient]: ecs =>
              ZIO.attemptBlocking:
                ecs.createService(
                  CreateServiceRequest
                    .builder()
                    .cluster(config.ecsCluster)
                    .serviceName(serviceName)
                    .taskDefinition(taskDefArn)
                    .desiredCount(1)
                    .launchType("FARGATE")
                    .build())
          *> Console.printLine(s"  Created service: $serviceName").orDie
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // ALB: Listener rule for routing
  // ─────────────────────────────────────────────────────────────────────────────

  private def configureAlbRule(
      slug: String): ZIO[PushConfig & ElasticLoadBalancingV2Client, CliError, Unit] =
    for
      config <- ZIO.service[PushConfig]
      _ <-
        if config.albListenerArn.isEmpty then
          Console.printLine("  [skip] No ALB listener ARN configured").orDie
        else
          val tgName = s"captal-$slug"
          for
            tgArn <- ensureTargetGroup(tgName, config)
            _     <- upsertAlbRule(slug, tgArn, config)
          yield ()
    yield ()

  private def ensureTargetGroup(
      name: String,
      config: PushConfig): ZIO[ElasticLoadBalancingV2Client, CliError, String] =
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
                      .vpcId(config.vpcId)
                      .targetType(TargetTypeEnum.IP)
                      .healthCheckPath("/health")
                      .build())
                  .targetGroups()
                  .get(0)
                  .targetGroupArn()

  private def upsertAlbRule(
      slug: String,
      tgArn: String,
      config: PushConfig): ZIO[ElasticLoadBalancingV2Client, CliError, Unit] =
    aws("ELBv2 rules"):
      ZIO.serviceWithZIO[ElasticLoadBalancingV2Client]: elbv2 =>
        ZIO.attemptBlocking:
          val hostPattern = s"$slug.captal.app"
          val forwardAction = Action.builder().`type`(ActionTypeEnum.FORWARD).targetGroupArn(tgArn).build()
          val rules = elbv2.describeRules(
            DescribeRulesRequest.builder().listenerArn(config.albListenerArn).build())

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
                .listenerArn(config.albListenerArn)
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

  /** Wrap an AWS SDK call, mapping exceptions to CliError.AwsError. */
  private def aws[R, A](operation: String)(effect: ZIO[R, Throwable, A]): ZIO[R, CliError, A] =
    effect.mapError(CliError.AwsError(operation, _))

  /** Execute an external process, failing with the given error on non-zero exit. */
  private def execProcess(cmd: String*)(onFail: => CliError): IO[CliError, Unit] =
    ZIO
      .attemptBlocking:
        val exitCode = new ProcessBuilder(cmd*)
          .inheritIO()
          .start()
          .waitFor()
        if exitCode != 0 then throw new RuntimeException(s"exit code $exitCode")
      .mapError(_ => onFail)
      .unit

  private def gzipBytes(data: Array[Byte]): Array[Byte] =
    val bos = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(bos)
    gzip.write(data)
    gzip.close()
    bos.toByteArray
