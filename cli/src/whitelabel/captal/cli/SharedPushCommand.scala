package whitelabel.captal.cli

import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{
  AssignPublicIp,
  AwsVpcConfiguration,
  Compatibility,
  ContainerDefinition,
  KeyValuePair,
  LogConfiguration,
  LogDriver,
  NetworkConfiguration,
  NetworkMode,
  RegisterTaskDefinitionRequest,
  RunTaskRequest
}
import zio.*

/** Runs shared provisioning (surveys + advertisers) via an ephemeral ECS task. */
object SharedPushCommand:

  type Env = CaptalConfig & EcsClient

  def run: ZIO[Env, CliError, Unit] =
    for
      _ <- Console.printLine("Deploying shared resources...").orDie

      _ <- Console.printLine("[1/2] Registering ephemeral task definition...").orDie
      taskDefArn <- registerTaskDefinition

      _ <- Console.printLine("[2/2] Running shared provisioning task...").orDie
      _ <- runEphemeralTask(taskDefArn)

      _ <- Console.printLine("Shared provisioning complete").orDie
    yield ()

  private def registerTaskDefinition: ZIO[CaptalConfig & EcsClient, CliError, String] =
    for
      config <- ZIO.service[CaptalConfig]
      arn <- aws("ECS registerTaskDefinition"):
        ZIO.serviceWithZIO[EcsClient]: ecs =>
          ZIO.attemptBlocking:
            val container = ContainerDefinition
              .builder()
              .name("captal-shared")
              .image(config.image)
              .essential(true)
              .command("java", "-cp", "infra.jar", "whitelabel.captal.infra.SharedProvision")
              .environment(
                KeyValuePair.builder().name("SHARED_DIR").value("/etc/captal/shared").build(),
                KeyValuePair.builder().name("DB_URL").value(config.database.url).build())
              .logConfiguration(
                LogConfiguration
                  .builder()
                  .logDriver(LogDriver.AWSLOGS)
                  .options(java.util.Map.of(
                    "awslogs-group", "/ecs/captal-shared",
                    "awslogs-region", config.aws.region,
                    "awslogs-stream-prefix", "ecs"))
                  .build())
              .build()

            val builder = RegisterTaskDefinitionRequest
              .builder()
              .family("captal-shared-provision")
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

  private def runEphemeralTask(taskDefArn: String): ZIO[CaptalConfig & EcsClient, CliError, Unit] =
    for
      config <- ZIO.service[CaptalConfig]
      netConfig = NetworkConfiguration
        .builder()
        .awsvpcConfiguration(
          AwsVpcConfiguration
            .builder()
            .subnets(config.ecs.subnets*)
            .securityGroups(config.ecs.securityGroups*)
            .assignPublicIp(AssignPublicIp.ENABLED)
            .build())
        .build()
      taskArn <- aws("ECS runTask"):
        ZIO.serviceWithZIO[EcsClient]: ecs =>
          ZIO.attemptBlocking:
            val resp = ecs.runTask(
              RunTaskRequest
                .builder()
                .cluster(config.ecs.cluster)
                .taskDefinition(taskDefArn)
                .launchType("FARGATE")
                .networkConfiguration(netConfig)
                .build())
            if resp.tasks().isEmpty then
              throw new RuntimeException(s"Failed to start task: ${resp.failures()}")
            resp.tasks().get(0).taskArn()
      _ <- Console.printLine(s"  Started task: $taskArn").orDie
      _ <- pollTaskCompletion(config.ecs.cluster, taskArn)
    yield ()

  private def pollTaskCompletion(cluster: String, taskArn: String): ZIO[EcsClient, CliError, Unit] =
    aws("ECS describeTasks"):
      ZIO.serviceWithZIO[EcsClient]: ecs =>
        def poll: Task[Unit] =
          for
            _ <- ZIO.sleep(5.seconds)
            resp <- ZIO.attemptBlocking:
              ecs.describeTasks(
                software.amazon.awssdk.services.ecs.model.DescribeTasksRequest
                  .builder().cluster(cluster).tasks(taskArn).build())
            task = resp.tasks().get(0)
            status = task.lastStatus()
            _ <- Console.printLine(s"  Task status: $status").orDie
            _ <-
              if status == "STOPPED" then
                val exitCode = task.containers().get(0).exitCode()
                if exitCode == 0 then ZIO.logInfo("Shared provisioning task completed successfully")
                else ZIO.fail(new RuntimeException(s"Task exited with code $exitCode"))
              else poll
          yield ()
        poll

  private def aws[R, A](operation: String)(effect: ZIO[R, Throwable, A]): ZIO[R, CliError, A] =
    effect.mapError(CliError.AwsError(operation, _))
