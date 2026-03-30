package whitelabel.captal.cli

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.s3.S3Client
import zio.*

/** Scoped ZIO layers for AWS SDK clients. Each client is automatically closed when the scope ends. */
object AwsLayers:
  private val region: Region = Region.of(
    sys.env.getOrElse("AWS_REGION", sys.env.getOrElse("AWS_DEFAULT_REGION", "us-east-1")))

  val s3: ZLayer[Any, Throwable, S3Client] = ZLayer.scoped:
    ZIO.acquireRelease(
      ZIO.attempt(S3Client.builder().region(region).build())
    )(c => ZIO.succeed(c.close()))

  val ecr: ZLayer[Any, Throwable, EcrClient] = ZLayer.scoped:
    ZIO.acquireRelease(
      ZIO.attempt(EcrClient.builder().region(region).build())
    )(c => ZIO.succeed(c.close()))

  val ecs: ZLayer[Any, Throwable, EcsClient] = ZLayer.scoped:
    ZIO.acquireRelease(
      ZIO.attempt(EcsClient.builder().region(region).build())
    )(c => ZIO.succeed(c.close()))

  val elbv2: ZLayer[Any, Throwable, ElasticLoadBalancingV2Client] = ZLayer.scoped:
    ZIO.acquireRelease(
      ZIO.attempt(ElasticLoadBalancingV2Client.builder().region(region).build())
    )(c => ZIO.succeed(c.close()))
