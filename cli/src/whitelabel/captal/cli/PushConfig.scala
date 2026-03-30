package whitelabel.captal.cli

import zio.*

/** Configuration for push command, read from environment variables. */
final case class PushConfig(
    s3Bucket: String,
    ecsCluster: String,
    albListenerArn: String,
    vpcId: String,
    ecrRepo: String,
    dbUrl: String,
    taskRoleArn: Option[String],
    executionRoleArn: String)

object PushConfig:
  val layer: ZLayer[Any, CliError, PushConfig] = ZLayer.fromZIO:
    ZIO
      .attempt:
        def env(key: String): String = sys.env.getOrElse(key, "")
        val s3Bucket = env("CAPTAL_S3_BUCKET")
        val ecsCluster = env("CAPTAL_ECS_CLUSTER")
        val albListenerArn = env("CAPTAL_ALB_LISTENER_ARN")
        val vpcId = env("CAPTAL_VPC_ID")
        val ecrRepo = env("CAPTAL_ECR_REPO").ifEmpty("captal")
        val dbUrl = env("DB_URL")
        val taskRoleArn = Option(env("CAPTAL_TASK_ROLE_ARN")).filter(_.nonEmpty)
        val executionRoleArn = env("CAPTAL_EXECUTION_ROLE_ARN")

        val required = List(
          "CAPTAL_S3_BUCKET"         -> s3Bucket,
          "CAPTAL_ECS_CLUSTER"       -> ecsCluster,
          "CAPTAL_ALB_LISTENER_ARN"  -> albListenerArn,
          "CAPTAL_VPC_ID"            -> vpcId,
          "DB_URL"                   -> dbUrl,
          "CAPTAL_EXECUTION_ROLE_ARN" -> executionRoleArn)
        val missing = required.collect { case (name, value) if value.isEmpty => name }

        if missing.nonEmpty then throw CliError.MissingEnvVars(missing)
        PushConfig(s3Bucket, ecsCluster, albListenerArn, vpcId, ecrRepo, dbUrl, taskRoleArn, executionRoleArn)
      .mapError:
        case e: CliError => e
        case e           => CliError.AwsError("config", e)

  extension (s: String) private def ifEmpty(default: String): String = if s.isEmpty then default else s
