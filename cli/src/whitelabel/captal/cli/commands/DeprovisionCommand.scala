package whitelabel.captal.cli.commands

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.services.cloudfront.CloudFrontClient
import software.amazon.awssdk.services.cloudfront.model.{
  CreateInvalidationRequest,
  InvalidationBatch,
  Paths as CfPaths
}
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.{
  DeleteLogGroupRequest,
  ResourceNotFoundException as LogsResourceNotFoundException
}
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.ecr.model.{
  BatchDeleteImageRequest,
  ImageIdentifier,
  ListImagesRequest,
  RepositoryNotFoundException
}
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{
  DeleteServiceRequest,
  DeregisterTaskDefinitionRequest,
  DescribeServicesRequest,
  ListTaskDefinitionsRequest,
  TaskDefinitionStatus,
  UpdateServiceRequest
}
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  DeleteRuleRequest,
  DeleteTargetGroupRequest,
  DescribeRulesRequest,
  DescribeTargetGroupsRequest,
  TargetGroupNotFoundException
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  Delete,
  DeleteObjectsRequest,
  ListObjectsV2Request,
  ObjectIdentifier
}
import whitelabel.captal.cli.{CaptalConfig, CliError, Output}
import zio.*

/** Inverse of [[PushCommand]]: tears down all AWS resources for a location while leaving the local
  * `locations/<slug>/` directory untouched, so the same slug can be re-provisioned later from the
  * same config files.
  */
object DeprovisionCommand:

  type Env =
    CaptalConfig & S3Client & EcsClient & ElasticLoadBalancingV2Client & EcrClient &
      CloudFrontClient & CloudWatchLogsClient

  def run(slug: String, skipPrompt: Boolean): ZIO[Env, CliError, Unit] =
    for
      _ <- Output.header(s"Deprovisioning location '$slug'")
      _ <- Output.warn("This will permanently delete the AWS infrastructure for this location:")
      _ <- Output.detail("  - ALB listener rule")
      _ <- Output.detail("  - ECS service + task definitions")
      _ <- Output.detail("  - ALB target group")
      _ <- Output.detail("  - CloudWatch log group")
      _ <- Output.detail("  - ECR image tags matching this slug")
      _ <- Output.detail(s"  - S3 assets under <bucket>/$slug/")
      _ <- Output.detail(s"  - CloudFront cache for /$slug/*")
      _ <- Output.info(s"The local locations/$slug/ directory is NOT touched.")
      _ <- confirmOrAbort(slug, skipPrompt)

      _ <- Output.step(1, 8, "Removing ALB listener rule...")
      _ <- removeAlbRule(slug)

      _ <- Output.step(2, 8, "Draining + deleting ECS service...")
      _ <- deleteEcsService(slug)

      _ <- Output.step(3, 8, "Deleting target group...")
      _ <- deleteTargetGroup(s"captal-$slug")

      _ <- Output.step(4, 8, "Deregistering task definitions...")
      _ <- deregisterTaskDefinitions(s"captal-$slug")

      _ <- Output.step(5, 8, "Deleting CloudWatch log group...")
      _ <- deleteLogGroup(s"/ecs/captal-$slug")

      _ <- Output.step(6, 8, "Deleting per-location ECR images...")
      _ <- deleteEcrImages(slug)

      _ <- Output.step(7, 8, "Deleting S3 assets...")
      _ <- deleteS3Assets(slug)

      _ <- Output.step(8, 8, "Invalidating CloudFront...")
      _ <- invalidateCdn(slug)

      _ <- Output.success(s"Deprovisioned '$slug'. Local locations/$slug/ kept intact.")
    yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // Confirmation
  // ─────────────────────────────────────────────────────────────────────────────

  private def confirmOrAbort(slug: String, skipPrompt: Boolean): IO[CliError, Unit] =
    if skipPrompt then
      ZIO.unit
    else
      for
        _      <- Console.print(s"\nProceed with deprovisioning '$slug'? [y/N]: ").orDie
        answer <- Console.readLine.orDie
        _      <-
          ZIO.unless(answer.trim.equalsIgnoreCase("y") || answer.trim.equalsIgnoreCase("yes"))(
            ZIO.fail(CliError.ConfigError(s"Aborted by user")))
      yield ()

  // ─────────────────────────────────────────────────────────────────────────────
  // ALB: remove the listener rule for /<slug>/api/*
  // ─────────────────────────────────────────────────────────────────────────────

  private def removeAlbRule(
      slug: String): ZIO[CaptalConfig & ElasticLoadBalancingV2Client, CliError, Unit] =
    aws("ELBv2 deleteRule"):
      ZIO.serviceWithZIO[CaptalConfig]: config =>
        ZIO.serviceWithZIO[ElasticLoadBalancingV2Client]: elbv2 =>
          ZIO.attemptBlocking:
            val pathPattern = s"/$slug/api/*"
            val rules = elbv2.describeRules(
              DescribeRulesRequest.builder().listenerArn(config.aws.alb.listenerArn).build())
            val matching = rules
              .rules()
              .stream()
              .filter: r =>
                r.conditions()
                  .stream()
                  .anyMatch: c =>
                    c.pathPatternConfig() != null &&
                      c.pathPatternConfig().values().contains(pathPattern)
              .toArray
              .toList
              .collect {
                case r: software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule =>
                  r
              }
            if matching.isEmpty then
              java.lang.System.out.println(s"  (already gone) no rule for $pathPattern")
            else
              matching.foreach: rule =>
                elbv2.deleteRule(DeleteRuleRequest.builder().ruleArn(rule.ruleArn()).build())
                java.lang.System.out.println(s"  Deleted ALB rule for $pathPattern")

  // ─────────────────────────────────────────────────────────────────────────────
  // ECS: delete the service (force=true kills tasks immediately)
  // ─────────────────────────────────────────────────────────────────────────────

  private def deleteEcsService(slug: String): ZIO[CaptalConfig & EcsClient, CliError, Unit] =
    aws("ECS deleteService"):
      ZIO.serviceWithZIO[CaptalConfig]: config =>
        ZIO.serviceWithZIO[EcsClient]: ecs =>
          ZIO.attemptBlocking:
            val serviceName = s"captal-$slug"
            val describe = ecs.describeServices(
              DescribeServicesRequest
                .builder()
                .cluster(config.aws.ecs.cluster)
                .services(serviceName)
                .build())
            val active = describe.services().stream().anyMatch(_.status() == "ACTIVE")
            if !active then
              java.lang.System.out.println(s"  (already gone) service $serviceName")
            else
              // Scale down first so ECS drains tasks gracefully; force=true on delete
              // then handles whatever is still running.
              ecs.updateService(
                UpdateServiceRequest
                  .builder()
                  .cluster(config.aws.ecs.cluster)
                  .service(serviceName)
                  .desiredCount(0)
                  .build())
              ecs.deleteService(
                DeleteServiceRequest
                  .builder()
                  .cluster(config.aws.ecs.cluster)
                  .service(serviceName)
                  .force(true)
                  .build())
              java.lang.System.out.println(s"  Deleted service $serviceName")
            end if

  // ─────────────────────────────────────────────────────────────────────────────
  // ALB: delete the target group (must be after service is gone — no consumers)
  // ─────────────────────────────────────────────────────────────────────────────

  private def deleteTargetGroup(name: String): ZIO[ElasticLoadBalancingV2Client, CliError, Unit] =
    aws("ELBv2 deleteTargetGroup"):
      ZIO.serviceWithZIO[ElasticLoadBalancingV2Client]: elbv2 =>
        ZIO.attemptBlocking:
          try
            val arn = elbv2
              .describeTargetGroups(DescribeTargetGroupsRequest.builder().names(name).build())
              .targetGroups()
              .get(0)
              .targetGroupArn()
            elbv2.deleteTargetGroup(DeleteTargetGroupRequest.builder().targetGroupArn(arn).build())
            java.lang.System.out.println(s"  Deleted target group $name")
          catch
            case _: TargetGroupNotFoundException =>
              java.lang.System.out.println(s"  (already gone) target group $name")

  // ─────────────────────────────────────────────────────────────────────────────
  // ECS: deregister all task definition revisions in the family
  // ─────────────────────────────────────────────────────────────────────────────

  private def deregisterTaskDefinitions(family: String): ZIO[EcsClient, CliError, Unit] =
    aws("ECS deregisterTaskDefinition"):
      ZIO.serviceWithZIO[EcsClient]: ecs =>
        ZIO.attemptBlocking:
          // Active revisions only — INACTIVE ones don't cost anything and can't be redeployed.
          val arns = ecs
            .listTaskDefinitionsPaginator(
              ListTaskDefinitionsRequest
                .builder()
                .familyPrefix(family)
                .status(TaskDefinitionStatus.ACTIVE)
                .build())
            .stream()
            .flatMap(_.taskDefinitionArns().stream())
            .toArray
            .toList
            .collect { case s: String =>
              s
            }
          if arns.isEmpty then
            java.lang.System.out.println(s"  (already gone) no active task defs for $family")
          else
            arns.foreach: arn =>
              ecs.deregisterTaskDefinition(
                DeregisterTaskDefinitionRequest.builder().taskDefinition(arn).build())
            java.lang.System.out.println(s"  Deregistered ${arns.size} task definition(s)")

  // ─────────────────────────────────────────────────────────────────────────────
  // CloudWatch Logs: delete the per-location log group
  // ─────────────────────────────────────────────────────────────────────────────

  private def deleteLogGroup(name: String): ZIO[CloudWatchLogsClient, CliError, Unit] =
    aws("CloudWatch Logs deleteLogGroup"):
      ZIO.serviceWithZIO[CloudWatchLogsClient]: logs =>
        ZIO.attemptBlocking:
          try
            logs.deleteLogGroup(DeleteLogGroupRequest.builder().logGroupName(name).build())
            java.lang.System.out.println(s"  Deleted log group $name")
          catch
            case _: LogsResourceNotFoundException =>
              java.lang.System.out.println(s"  (already gone) log group $name")

  // ─────────────────────────────────────────────────────────────────────────────
  // ECR: delete images tagged "<slug>-*" from the locations repo
  // ─────────────────────────────────────────────────────────────────────────────

  private def deleteEcrImages(slug: String): ZIO[CaptalConfig & EcrClient, CliError, Unit] =
    aws("ECR batchDeleteImage"):
      ZIO.serviceWithZIO[CaptalConfig]: config =>
        ZIO.serviceWithZIO[EcrClient]: ecr =>
          ZIO.attemptBlocking:
            val repoName = ecrRepoNameFromUri(config.aws.images.locations)
            val prefix = s"$slug-"
            try
              val matching = ecr
                .listImagesPaginator(ListImagesRequest.builder().repositoryName(repoName).build())
                .stream()
                .flatMap(_.imageIds().stream())
                .filter: id =>
                  val tag = id.imageTag()
                  tag != null && tag.startsWith(prefix)
                .toArray
                .toList
                .collect { case id: ImageIdentifier =>
                  id
                }
              if matching.isEmpty then
                java.lang.System.out.println(s"  (already gone) no images for $repoName:$slug-*")
              else
                // BatchDelete handles up to 100 per call — chunk if needed.
                matching
                  .grouped(100)
                  .foreach: batch =>
                    ecr.batchDeleteImage(
                      BatchDeleteImageRequest
                        .builder()
                        .repositoryName(repoName)
                        .imageIds(batch.asJava)
                        .build())
                java
                  .lang
                  .System
                  .out
                  .println(s"  Deleted ${matching.size} image tag(s) from $repoName")
            catch
              case _: RepositoryNotFoundException =>
                java.lang.System.out.println(s"  (already gone) repo $repoName not found")
            end try

  /** Strip the registry/account prefix from an ECR image URI to get the repo name. */
  private def ecrRepoNameFromUri(uri: String): String =
    val withoutTag =
      uri.indexOf(':') match
        case -1 =>
          uri
        case i =>
          uri.substring(0, i)
    withoutTag.indexOf('/') match
      case -1 =>
        withoutTag
      case i =>
        withoutTag.substring(i + 1)

  // ─────────────────────────────────────────────────────────────────────────────
  // S3: delete every object under "<slug>/" in the assets bucket
  // ─────────────────────────────────────────────────────────────────────────────

  private def deleteS3Assets(slug: String): ZIO[CaptalConfig & S3Client, CliError, Unit] =
    aws("S3 deleteObjects"):
      ZIO.serviceWithZIO[CaptalConfig]: config =>
        ZIO.serviceWithZIO[S3Client]: s3 =>
          ZIO.attemptBlocking:
            val prefix = s"$slug/"
            var continuation: Option[String] = None
            var totalDeleted = 0
            var done = false
            while !done do
              val reqBuilder = ListObjectsV2Request
                .builder()
                .bucket(config.aws.s3.bucket)
                .prefix(prefix)
              continuation.foreach(reqBuilder.continuationToken)
              val resp = s3.listObjectsV2(reqBuilder.build())
              val ids = resp
                .contents()
                .stream()
                .map(o => ObjectIdentifier.builder().key(o.key()).build())
                .toArray
                .toList
                .collect { case id: ObjectIdentifier =>
                  id
                }
              if ids.nonEmpty then
                // DeleteObjects accepts up to 1000 keys per request — chunk for safety.
                ids
                  .grouped(1000)
                  .foreach: batch =>
                    s3.deleteObjects(
                      DeleteObjectsRequest
                        .builder()
                        .bucket(config.aws.s3.bucket)
                        .delete(Delete.builder().objects(batch.asJava).build())
                        .build())
                totalDeleted += ids.size
              if resp.isTruncated then
                continuation = Option(resp.nextContinuationToken())
              else
                done = true
            end while
            if totalDeleted == 0 then
              java
                .lang
                .System
                .out
                .println(s"  (already gone) no objects under s3://${config.aws.s3.bucket}/$prefix")
            else
              java
                .lang
                .System
                .out
                .println(s"  Deleted $totalDeleted object(s) from s3://${config.aws.s3.bucket}/$prefix")

  // ─────────────────────────────────────────────────────────────────────────────
  // CloudFront: invalidate to clear cached responses for this slug
  // ─────────────────────────────────────────────────────────────────────────────

  private def invalidateCdn(slug: String): ZIO[CaptalConfig & CloudFrontClient, CliError, Unit] =
    Clock.instant.flatMap: now =>
      aws("CloudFront createInvalidation"):
        ZIO.serviceWithZIO[CloudFrontClient]: cf =>
          ZIO.serviceWithZIO[CaptalConfig]: config =>
            ZIO.attemptBlocking:
              cf.createInvalidation(
                CreateInvalidationRequest
                  .builder()
                  .distributionId(config.aws.cloudfront.distributionId)
                  .invalidationBatch(
                    InvalidationBatch
                      .builder()
                      .callerReference(now.toString)
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
end DeprovisionCommand
