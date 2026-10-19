package whitelabel.captal.mcp.aws

import java.net.URI

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.{
  CreateLogGroupRequest,
  ResourceAlreadyExistsException
}
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{
  AssignPublicIp,
  AwsVpcConfiguration,
  ClusterNotFoundException,
  Compatibility,
  ContainerDefinition,
  CreateServiceRequest,
  DeleteServiceRequest,
  DeploymentConfiguration,
  DeregisterTaskDefinitionRequest,
  DescribeServicesRequest,
  KeyValuePair,
  LaunchType,
  ListTaskDefinitionsRequest,
  LoadBalancer,
  LogConfiguration,
  LogDriver,
  NetworkConfiguration,
  NetworkMode,
  PortMapping,
  RegisterTaskDefinitionRequest,
  ServiceNotFoundException,
  UpdateServiceRequest
}
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  Action,
  ActionTypeEnum,
  CreateRuleRequest,
  CreateTargetGroupRequest,
  DeleteRuleRequest,
  DeleteTargetGroupRequest,
  DescribeRulesRequest,
  DescribeTargetGroupsRequest,
  DescribeTargetHealthRequest,
  FixedResponseActionConfig,
  Matcher,
  ModifyRuleRequest,
  PathPatternConditionConfig,
  ProtocolEnum,
  RuleCondition,
  TargetGroupNotFoundException,
  TargetHealthStateEnum,
  TargetTypeEnum
}
import zio.*

/** Everything a location's service needs that is not a location: the cluster they share, the
  * subnets they run in, the listener they hang a rule off, the image they all run. Read once at
  * startup, so a deploy names only a slug.
  *
  * `DB_URL` is this server's own, injected into every task definition. The database therefore sits
  * outside what a deploy creates and outside what a teardown destroys.
  */
final case class DeployTargets(
    cluster: String,
    vpcId: String,
    subnets: List[String],
    securityGroup: String,
    listenerArn: String,
    executionRoleArn: String,
    taskRoleArn: String,
    image: String,
    dbUrl: String,
    region: String,
    /** The port the API listens on, and the port the target group forwards to. */
    containerPort: Int = 8080):

  /** `captal-<slug>`. AWS caps a target group name at 32 characters, which is where
    * `LocationTools.MaxSlugLength` comes from; the two have to agree or a slug `create_location`
    * accepted produces a deploy that cannot name its target group.
    */
  def targetGroupName(slug: String): String = s"captal-$slug"

  def serviceName(slug: String): String = s"captal-$slug"

  def logGroup(slug: String): String = s"/ecs/captal-$slug"

  /** What CloudFront forwards to the ALB, and what the API answers on. `api.ApiBase` bakes the slug
    * into every route's path at boot, so one service serves exactly one prefix.
    */
  def pathPattern(slug: String): String = s"/$slug/api/*"

  /** Set when the target group is created and only then: `ensureTargetGroup` never calls
    * `modifyTargetGroup`, so an existing group silently keeps whatever path it already has.
    */
  def healthCheckPath(slug: String): String = s"/$slug/api/health"
end DeployTargets

object DeployTargets:

  private def required(name: String): ZIO[Any, Throwable, String] = System
    .env(name)
    .orDie
    .someOrFail(new IllegalStateException(s"$name is required to deploy a location"))

  /** `None` when `ECS_CLUSTER` is unset, so a server with no deploy configuration starts and simply
    * has no deploy tools. With it set, every other variable below is required and startup fails
    * without it.
    */
  val layer: ZLayer[Any, Throwable, Option[DeployTargets]] = ZLayer:
    System
      .env("ECS_CLUSTER")
      .orDie
      .flatMap:
        case None =>
          ZIO
            .logInfo(
              "ECS_CLUSTER is unset, so deploy_location, pause_location, resume_location and destroy_location are not registered. Content tools are unaffected.")
            .as(None)
        case Some(cluster) =>
          for
            vpcId    <- required("VPC_ID")
            subnets  <- required("SUBNET_IDS")
            group    <- required("SECURITY_GROUP_ID")
            listener <- required("ALB_LISTENER_ARN")
            execRole <- required("TASK_EXECUTION_ROLE_ARN")
            taskRole <- required("TASK_ROLE_ARN")
            image    <- required("API_IMAGE")
            dbUrl    <- required("DB_URL")
            region   <- System.env("AWS_REGION").orDie.map(_.getOrElse("us-east-1"))
          yield Some(
            DeployTargets(
              cluster = cluster,
              vpcId = vpcId,
              // `SUBNET_IDS` is comma-separated, as a Terraform output carries it.
              subnets = subnets.split(",").map(_.trim).filter(_.nonEmpty).toList,
              securityGroup = group,
              listenerArn = listener,
              executionRoleArn = execRole,
              taskRoleArn = taskRole,
              image = image,
              dbUrl = dbUrl,
              region = region
            ))
end DeployTargets

/** The AWS calls a deploy makes, and nothing else.
  *
  * The order is log group, target group, listener rule, task definition, service. The rule has to
  * go in before the service: `createService` with a `loadBalancers` block fails with `400 no
  * associated load balancer` when the target group is not yet reachable through a listener.
  */
final case class InfraStore(
    ecs: EcsClient,
    elb: ElasticLoadBalancingV2Client,
    logs: CloudWatchLogsClient,
    targets: DeployTargets):

  /** Idempotent by catching `ResourceAlreadyExists` rather than describing first, which would race
    * with itself.
    */
  def ensureLogGroup(slug: String): ZIO[Any, String, String] =
    val name = targets.logGroup(slug)
    ZIO
      .attemptBlocking(
        logs.createLogGroup(CreateLogGroupRequest.builder().logGroupName(name).build()))
      .as(name)
      .catchSome { case _: ResourceAlreadyExistsException =>
        ZIO.succeed(name)
      }
      .mapError(e => s"Could not create log group $name: ${e.getMessage}")

  def ensureTargetGroup(slug: String): ZIO[Any, String, String] =
    val name = targets.targetGroupName(slug)
    val find = ZIO
      .attemptBlocking(
        elb
          .describeTargetGroups(DescribeTargetGroupsRequest.builder().names(name).build())
          .targetGroups()
          .get(0)
          .targetGroupArn())
      .asSome
      .catchSome { case _: TargetGroupNotFoundException =>
        ZIO.none
      }
    find
      .flatMap:
        case Some(arn) =>
          ZIO.succeed(arn)
        case None =>
          ZIO.attemptBlocking(
            elb
              .createTargetGroup(
                CreateTargetGroupRequest
                  .builder()
                  .name(name)
                  .protocol(ProtocolEnum.HTTP)
                  .port(targets.containerPort)
                  .vpcId(targets.vpcId)
                  // Fargate tasks have their own ENI, so the target is an address; `instance` would
                  // be rejected at registration time.
                  .targetType(TargetTypeEnum.IP)
                  .healthCheckPath(targets.healthCheckPath(slug))
                  .matcher(Matcher.builder().httpCode("200").build())
                  .build())
              .targetGroups()
              .get(0)
              .targetGroupArn())
      .mapError(e => s"Could not create target group $name: ${e.getMessage}")
  end ensureTargetGroup

  /** Upserted by path pattern, which is what identifies a location's rule; the priority is an
    * arbitrary slot. Re-deploying a location must not add a second rule for the same path.
    */
  def ensureRule(slug: String, targetGroupArn: String): ZIO[Any, String, String] =
    val pattern = targets.pathPattern(slug)
    ZIO
      .attemptBlocking:
        val existing = elb
          .describeRules(DescribeRulesRequest.builder().listenerArn(targets.listenerArn).build())
          .rules()
        // The SDK's setter is named `type`, a Scala keyword, so it has to be backticked.
        val forward = Action
          .builder()
          .`type`(ActionTypeEnum.FORWARD)
          .targetGroupArn(targetGroupArn)
          .build()
        val condition = RuleCondition
          .builder()
          .field("path-pattern")
          .pathPatternConfig(PathPatternConditionConfig.builder().values(pattern).build())
          .build()
        import scala.jdk.CollectionConverters.*
        val mine = existing
          .asScala
          .find(_.conditions().asScala.exists(_.values().asScala.contains(pattern)))
        mine match
          case Some(rule) =>
            elb.modifyRule(
              ModifyRuleRequest.builder().ruleArn(rule.ruleArn()).actions(forward).build())
            rule.ruleArn()
          case None =>
            // The default rule carries no numeric priority; the next free slot is one past the
            // highest in use, or 10 when none are.
            val used =
              existing.asScala.flatMap(r => scala.util.Try(r.priority().toInt).toOption).toList
            val priority =
              if used.isEmpty then
                10
              else
                used.max + 1
            elb
              .createRule(
                CreateRuleRequest
                  .builder()
                  .listenerArn(targets.listenerArn)
                  .priority(priority)
                  .conditions(condition)
                  .actions(forward)
                  .build())
              .rules()
              .get(0)
              .ruleArn()
        end match
      .mapError(e => s"Could not upsert the ALB rule for $pattern: ${e.getMessage}")
  end ensureRule

  /** A new revision every deploy: a task definition is immutable once registered, and
    * `updateService` takes a revision rather than a diff.
    *
    * `LOCATION_SLUG` is what makes this per-location. The API reads it once at boot and bakes it
    * into its route paths, so one container serves exactly one portal.
    */
  def registerTaskDefinition(slug: String): ZIO[Any, String, String] = ZIO
    .attemptBlocking:
      val container = ContainerDefinition
        .builder()
        .name("api")
        .image(targets.image)
        .essential(true)
        .portMappings(PortMapping.builder().containerPort(targets.containerPort).build())
        .environment(
          KeyValuePair.builder().name("LOCATION_SLUG").value(slug).build(),
          KeyValuePair.builder().name("DB_URL").value(targets.dbUrl).build())
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
                  targets.logGroup(slug),
                  "awslogs-region",
                  targets.region,
                  "awslogs-stream-prefix",
                  "api"))
            .build())
        .build()
      ecs
        .registerTaskDefinition(
          RegisterTaskDefinitionRequest
            .builder()
            .family(targets.serviceName(slug))
            .requiresCompatibilities(Compatibility.FARGATE)
            .networkMode(NetworkMode.AWSVPC)
            .cpu("256")
            .memory("512")
            .executionRoleArn(targets.executionRoleArn)
            // Required for ECS Exec, which is how an operator gets a shell into a running portal.
            .taskRoleArn(targets.taskRoleArn)
            .containerDefinitions(container)
            .build())
        .taskDefinition()
        .taskDefinitionArn()
    .mapError(e => s"Could not register the task definition for $slug: ${e.getMessage}")

  /** Create or update. An existing service is updated in place so a redeploy does not drop the
    * location's traffic; `updateService` has to carry `loadBalancers` too, or a service created
    * without a target group never acquires one.
    */
  def upsertService(
      slug: String,
      taskDefinitionArn: String,
      targetGroupArn: String): ZIO[Any, String, (String, Boolean)] =
    val name = targets.serviceName(slug)
    val network = NetworkConfiguration
      .builder()
      .awsvpcConfiguration(
        AwsVpcConfiguration
          .builder()
          .subnets(targets.subnets*)
          .securityGroups(targets.securityGroup)
          // Mirrors the topology Terraform builds, where `enable_nat_gateway` is false in every
          // environment: tasks sit in public subnets and are isolated by security group alone --
          // ingress on the container port from the load balancer's group and nothing else.
          //
          // A public subnet has no NAT, so a task with no address of its own has no route to ECR
          // and fails with `CannotPullContainerError` while every resource looks correct. If
          // `enable_nat_gateway` is ever turned on, this has to flip with it.
          .assignPublicIp(AssignPublicIp.ENABLED)
          .build())
      .build()
    val balancer = LoadBalancer
      .builder()
      .targetGroupArn(targetGroupArn)
      .containerName("api")
      .containerPort(targets.containerPort)
      .build()
    ZIO
      .attemptBlocking:
        import scala.jdk.CollectionConverters.*
        val existing = ecs
          .describeServices(
            DescribeServicesRequest.builder().cluster(targets.cluster).services(name).build())
          .services()
          .asScala
          // A deleted service lingers as INACTIVE and cannot be updated; it has to be created
          // again.
          .find(_.status() != "INACTIVE")
        existing match
          case Some(service) =>
            val arn = ecs
              .updateService(
                UpdateServiceRequest
                  .builder()
                  .cluster(targets.cluster)
                  .service(name)
                  .taskDefinition(taskDefinitionArn)
                  .desiredCount(1)
                  .networkConfiguration(network)
                  .loadBalancers(balancer)
                  .build())
              .service()
              .serviceArn()
            (arn, false)
          case None =>
            val arn = ecs
              .createService(
                CreateServiceRequest
                  .builder()
                  .cluster(targets.cluster)
                  .serviceName(name)
                  .taskDefinition(taskDefinitionArn)
                  .desiredCount(1)
                  .launchType(LaunchType.FARGATE)
                  .networkConfiguration(network)
                  .loadBalancers(balancer)
                  .enableExecuteCommand(true)
                  // The portal answers health checks only once it has resolved its location and
                  // connected to rqlite; without the grace period the target is killed before it
                  // ever reports healthy.
                  .healthCheckGracePeriodSeconds(180)
                  .deploymentConfiguration(
                    DeploymentConfiguration
                      .builder()
                      .minimumHealthyPercent(100)
                      .maximumPercent(200)
                      .build())
                  .build())
              .service()
              .serviceArn()
            (arn, true)
        end match
      .mapError(e => s"Could not create or update the service $name: ${e.getMessage}")
  end upsertService

  /** How many tasks the service is asked to run, or `None` when there is no live service. Zero is
    * what a pause leaves behind, so this is also how paused-ness is read back.
    */
  def desiredCount(slug: String): ZIO[Any, String, Option[Int]] = ZIO
    .attemptBlocking:
      import scala.jdk.CollectionConverters.*
      ecs
        .describeServices(
          DescribeServicesRequest
            .builder()
            .cluster(targets.cluster)
            .services(targets.serviceName(slug))
            .build())
        .services()
        .asScala
        .find(_.status() != "INACTIVE")
        .map(_.desiredCount().intValue)
    .mapError(e => s"Could not read the service for '$slug': ${e.getMessage}")

  /** Scales the service. The tasks stop or start; the service keeps its name, its target group and
    * its rule, so no ARN anywhere changes. This is what separates a pause from a destroy.
    */
  def scaleService(slug: String, count: Int): ZIO[Any, String, String] =
    val name = targets.serviceName(slug)
    ZIO
      .attemptBlocking:
        ecs
          .updateService(
            UpdateServiceRequest
              .builder()
              .cluster(targets.cluster)
              .service(name)
              .desiredCount(count)
              .build())
          .service()
          .serviceArn()
      .mapError(e => s"Could not scale $name to $count: ${e.getMessage}")

  /** Points this location's listener rule at a fixed response instead of its target group, so a
    * paused location answers rather than failing: a forward rule to an empty target group would
    * return a bare 503 that the portal reads as broken.
    *
    * JSON rather than HTML, since this rule only ever matches the API path.
    * [[InfraStore.PausedBody]] is the contract the portal reads.
    */
  def pauseRule(slug: String): ZIO[Any, String, String] =
    val pattern = targets.pathPattern(slug)
    ZIO
      .attemptBlocking:
        import scala.jdk.CollectionConverters.*
        val paused = Action
          .builder()
          .`type`(ActionTypeEnum.FIXED_RESPONSE)
          .fixedResponseConfig(
            FixedResponseActionConfig
              .builder()
              // 503 and not 200, so any cache or proxy in between treats this as "unavailable
              // now". No `Retry-After`: nothing here knows when the pause ends.
              .statusCode("503")
              .contentType("application/json")
              .messageBody(InfraStore.PausedBody)
              .build())
          .build()
        elb
          .describeRules(DescribeRulesRequest.builder().listenerArn(targets.listenerArn).build())
          .rules()
          .asScala
          .find(_.conditions().asScala.exists(_.values().asScala.contains(pattern))) match
          case Some(rule) =>
            elb.modifyRule(
              ModifyRuleRequest.builder().ruleArn(rule.ruleArn()).actions(paused).build())
            rule.ruleArn()
          case None =>
            throw new IllegalStateException(
              s"No listener rule matches '$pattern', so there is nothing to pause. Deploy the location first.")
      .mapError(e => s"Could not pause the rule for '$slug': ${e.getMessage}")
  end pauseRule

  /** How many tasks the service is actually running, or `None` when there is no live service.
    *
    * ECS's own count, usable where target health is not: a target group no rule forwards to reports
    * its targets `unused` and is never health-checked.
    */
  def runningCount(slug: String): ZIO[Any, String, Option[Int]] = ZIO
    .attemptBlocking:
      import scala.jdk.CollectionConverters.*
      ecs
        .describeServices(
          DescribeServicesRequest
            .builder()
            .cluster(targets.cluster)
            .services(targets.serviceName(slug))
            .build())
        .services()
        .asScala
        .find(_.status() != "INACTIVE")
        .map(_.runningCount().intValue)
    .mapError(e => s"Could not read the running count for '$slug': ${e.getMessage}")

  /** Waits until the running task count satisfies `settled`, and says whether it did within
    * `within`. No live service counts as zero.
    *
    * Returns rather than fails: what to do about a timeout belongs to the caller.
    */
  def awaitRunning(
      slug: String,
      settled: Int => Boolean,
      within: Duration): ZIO[Any, String, Boolean] = runningCount(slug)
    .map(_.getOrElse(0))
    .repeatUntil(settled)
    .timeout(within)
    .map(_.isDefined)

  /** How many of this location's targets the load balancer considers healthy.
    *
    * Only ever more than zero once a rule forwards to the target group: AWS reports a target group
    * nothing routes to as `unused` with `Target.NotInUse` and does not health-check it. So this is
    * the check *after* the rule is in place, never the gate on putting it there.
    */
  def healthyTargets(slug: String): ZIO[Any, String, Int] = ZIO
    .attemptBlocking:
      import scala.jdk.CollectionConverters.*
      val arn = elb
        .describeTargetGroups(
          DescribeTargetGroupsRequest.builder().names(targets.targetGroupName(slug)).build())
        .targetGroups()
        .asScala
        .head
        .targetGroupArn()
      elb
        .describeTargetHealth(DescribeTargetHealthRequest.builder().targetGroupArn(arn).build())
        .targetHealthDescriptions()
        .asScala
        .count(_.targetHealth().state() == TargetHealthStateEnum.HEALTHY)
    .mapError(e => s"Could not read target health for '$slug': ${e.getMessage}")

  /** Waits for one healthy target, and says whether it got one: the confirmation that a request
    * would be answered by a container rather than by a 503 from the load balancer.
    */
  def awaitHealthyTarget(slug: String, within: Duration): ZIO[Any, String, Boolean] =
    healthyTargets(slug).repeatUntil(_ > 0).timeout(within).map(_.isDefined)

  /** Whether this location's rule is answering with the paused body. Read from the rule's own
    * action, which is where the state lives: a pause writes nothing to the database.
    */
  def ruleIsPaused(slug: String): ZIO[Any, String, Boolean] =
    val pattern = targets.pathPattern(slug)
    ZIO
      .attemptBlocking:
        import scala.jdk.CollectionConverters.*
        elb
          .describeRules(DescribeRulesRequest.builder().listenerArn(targets.listenerArn).build())
          .rules()
          .asScala
          .filter(_.conditions().asScala.exists(_.values().asScala.contains(pattern)))
          .exists(_.actions().asScala.exists(_.`type`() == ActionTypeEnum.FIXED_RESPONSE))
      .mapError(e => s"Could not read the rule for '$slug': ${e.getMessage}")

  /** Everything a deploy made, removed in reverse order: service, rules, target group, task
    * definitions. A target group cannot go while a rule still forwards to it, and a registered
    * service holds it too. Every step tolerates its resource being absent, so a teardown can finish
    * what a failed one started.
    *
    * The log group is deliberately kept; `deleteLogGroup` is never called.
    *
    * Nothing in the database or in S3 is touched, so a destroyed location is one `deploy_location`
    * away from being back.
    */
  def destroy(slug: String): ZIO[Any, String, DestroyReport] =
    val serviceName = targets.serviceName(slug)
    val pattern = targets.pathPattern(slug)
    ZIO
      .attemptBlocking:
        import scala.jdk.CollectionConverters.*

        // The service first, forced so it goes with tasks still running.
        //
        // Looked up before it is deleted, rather than inferred from whether the delete threw:
        // `deleteService` succeeds again on a service already INACTIVE.
        val live =
          try
            ecs
              .describeServices(
                DescribeServicesRequest
                  .builder()
                  .cluster(targets.cluster)
                  .services(serviceName)
                  .build())
              .services()
              .asScala
              .exists(_.status() != "INACTIVE")
          catch
            case _: ClusterNotFoundException =>
              false
        val serviceGone =
          live && {
            try
              ecs.deleteService(
                DeleteServiceRequest
                  .builder()
                  .cluster(targets.cluster)
                  .service(serviceName)
                  .force(true)
                  .build())
              true
            catch
              case _: ServiceNotFoundException =>
                false
              case _: ClusterNotFoundException =>
                false
          }

        // Then the rule, which is what holds the target group.
        val ruleArns =
          elb
            .describeRules(DescribeRulesRequest.builder().listenerArn(targets.listenerArn).build())
            .rules()
            .asScala
            .filter(_.conditions().asScala.exists(_.values().asScala.contains(pattern)))
            .map(_.ruleArn())
            .toList
        ruleArns.foreach(arn => elb.deleteRule(DeleteRuleRequest.builder().ruleArn(arn).build()))

        val targetGroupGone =
          try
            val arn = elb
              .describeTargetGroups(
                DescribeTargetGroupsRequest.builder().names(targets.targetGroupName(slug)).build())
              .targetGroups()
              .asScala
              .head
              .targetGroupArn()
            elb.deleteTargetGroup(DeleteTargetGroupRequest.builder().targetGroupArn(arn).build())
            true
          catch
            case _: TargetGroupNotFoundException =>
              false

        // And the task definitions, which linger as ACTIVE revisions forever.
        //
        // `familyPrefix` is a prefix and not a name, so asking for `captal-cafe` also answers with
        // `captal-cafe-centro`. The family has to be re-checked here to keep a teardown inside its
        // own location.
        val family = targets.serviceName(slug)
        val revisions =
          ecs
            .listTaskDefinitions(ListTaskDefinitionsRequest.builder().familyPrefix(family).build())
            .taskDefinitionArns()
            .asScala
            .filter(arn => arn.split('/').last.split(':').head == family)
            .toList
        revisions.foreach(arn =>
          ecs.deregisterTaskDefinition(
            DeregisterTaskDefinitionRequest.builder().taskDefinition(arn).build()))

        DestroyReport(
          serviceDeleted = serviceGone,
          rulesDeleted = ruleArns.size,
          targetGroupDeleted = targetGroupGone,
          taskDefinitionsDeregistered = revisions.size,
          logGroup = targets.logGroup(slug)
        )
      .mapError(e => s"Could not tear '$slug' down: ${e.getMessage}")
  end destroy
end InfraStore

/** What a teardown removed. Counted rather than asserted: a teardown that follows a failed one
  * finds some of it already gone.
  */
final case class DestroyReport(
    serviceDeleted: Boolean,
    rulesDeleted: Int,
    targetGroupDeleted: Boolean,
    taskDefinitionsDeregistered: Int,
    logGroup: String)

object InfraStore:

  /** What the load balancer answers on a paused location's API path. A contract with the portal,
    * which reads it and goes to the maintenance page by itself; there is no schema between them.
    */
  val PausedBody: String = """{"status":"paused","reason":"maintenance"}"""

  /** Absent whenever [[DeployTargets]] is, so the deploy tools are never registered without the
    * configuration they need.
    */
  val layer: ZLayer[Option[DeployTargets], Throwable, Option[InfraStore]] = ZLayer.scoped:
    ZIO
      .service[Option[DeployTargets]]
      .flatMap:
        case None =>
          ZIO.none
        case Some(targets) =>
          for
            endpoint <- System.env("AWS_ENDPOINT_URL").orDie
            ecs <- ZIO.acquireRelease(ZIO.attempt(ecsClient(endpoint)))(c => ZIO.succeed(c.close()))
            elb <- ZIO.acquireRelease(ZIO.attempt(elbClient(endpoint)))(c => ZIO.succeed(c.close()))
            logs <-
              ZIO.acquireRelease(ZIO.attempt(logsClient(endpoint)))(c => ZIO.succeed(c.close()))
          yield Some(InfraStore(ecs, elb, logs, targets))

  // The same `AWS_ENDPOINT_URL` override the S3 client takes: LocalStack emulates ECS, ELBv2 and
  // Logs.
  private def ecsClient(endpoint: Option[String]) =
    endpoint.fold(EcsClient.create())(url =>
      EcsClient.builder().endpointOverride(URI.create(url)).build())

  private def elbClient(endpoint: Option[String]) =
    endpoint.fold(ElasticLoadBalancingV2Client.create())(url =>
      ElasticLoadBalancingV2Client.builder().endpointOverride(URI.create(url)).build())

  private def logsClient(endpoint: Option[String]) =
    endpoint.fold(CloudWatchLogsClient.create())(url =>
      CloudWatchLogsClient.builder().endpointOverride(URI.create(url)).build())
end InfraStore
