package whitelabel.captal.cli

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.s3.S3Client
import zio.*

/** Scoped ZIO layers for AWS SDK clients, configured from CaptalConfig. */
object AwsLayers:

  private def credentialsProvider(aws: CaptalConfig.Aws): Option[StaticCredentialsProvider] =
    aws.accessKeyId.zip(aws.secretAccessKey).headOption.map: (keyId, secret) =>
      aws.sessionToken match
        case Some(token) => StaticCredentialsProvider.create(AwsSessionCredentials.create(keyId, secret, token))
        case None        => StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret))

  val s3: ZLayer[CaptalConfig, Throwable, S3Client] = ZLayer.scoped:
    ZIO.serviceWithZIO[CaptalConfig]: config =>
      val region = Region.of(config.aws.region)
      ZIO.acquireRelease(ZIO.attempt {
        val builder = S3Client.builder().region(region)
        credentialsProvider(config.aws).foreach(builder.credentialsProvider)
        builder.build()
      })(c => ZIO.succeed(c.close()))

  val ecs: ZLayer[CaptalConfig, Throwable, EcsClient] = ZLayer.scoped:
    ZIO.serviceWithZIO[CaptalConfig]: config =>
      val region = Region.of(config.aws.region)
      ZIO.acquireRelease(ZIO.attempt {
        val builder = EcsClient.builder().region(region)
        credentialsProvider(config.aws).foreach(builder.credentialsProvider)
        builder.build()
      })(c => ZIO.succeed(c.close()))

  val elbv2: ZLayer[CaptalConfig, Throwable, ElasticLoadBalancingV2Client] = ZLayer.scoped:
    ZIO.serviceWithZIO[CaptalConfig]: config =>
      val region = Region.of(config.aws.region)
      ZIO.acquireRelease(ZIO.attempt {
        val builder = ElasticLoadBalancingV2Client.builder().region(region)
        credentialsProvider(config.aws).foreach(builder.credentialsProvider)
        builder.build()
      })(c => ZIO.succeed(c.close()))
