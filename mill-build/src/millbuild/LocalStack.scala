package millbuild

/** The LocalStack container's name, shared by `mcp` and `client`. */
val localStackContainer = "localstack-dev"

/** LocalStack's port, on the host and inside the container alike. Not its default 4566. */
val localStackPort = 4567

/** The LocalStack image version, the same for licensed and free editions. Versions are dates, such as `2026.9.1`.
  */
val localStackVersion = "2026.9.1"

/** The services the free edition starts: S3 is the only one this project uses there. */
val freeServices = "s3"

/** The services a licensed container starts with: everything `deploy_location` and its fixture call.
  *
  * `route53` is not called directly, but `servicediscovery.CreatePrivateDnsNamespace` requires it.
  * A service missing here fails as `UnrecognizedClientException`, which is not a credentials error.
  */
val licensedServices =
  "s3,logs,ecs,elbv2,cloudfront,ec2,iam,sts,efs,secretsmanager,servicediscovery,route53,ecr"

/** The LocalStack licence token, from `LOCALSTACK_AUTH_TOKEN` or `LOCALSTACK_TOKEN`. Never logged.
  *
  * Read from `env` (`Task.env`) rather than `System.getenv`: the Mill daemon outlives the invocation.
  */
def localStackToken(env: Map[String, String]): Option[String] = env
  .get("LOCALSTACK_AUTH_TOKEN")
  .orElse(env.get("LOCALSTACK_TOKEN"))
  .map(_.trim)
  .filter(_.nonEmpty)

/** The devenv process LocalStack runs as. */
val localStackProcess = "localstack"

/** Removes the LocalStack container and the containers it ran for ECS tasks. A new LocalStack knows none of
  * them, and the old rqlite tasks would keep its host port.
  */
def removeLocalStackContainers(containerName: String): Unit =
  def ids(filter: String) = os
    .proc("docker", "ps", "-aq", "-f", filter)
    .call(check = false, stderr = os.Pipe)
    .out
    .lines()
    .filter(_.nonEmpty)
  val all = ids(s"name=$containerName") ++ ids("name=ls-ecs-captal-")
  if all.nonEmpty then
    val _ = os.proc("docker", "rm", "-fv", all).call(check = false, stderr = os.Pipe)

/** Whether LocalStack answers its health endpoint. */
private def localStackHealthy(endpoint: String): Boolean = answers(s"$endpoint/_localstack/health")

/** Runs LocalStack as the devenv process [[localStackProcess]], unless it already runs this configuration,
  * and returns its endpoint. Creates no resources itself: [[ensureLocalStackTofu]] does. A new container
  * takes the OpenTofu state of any earlier one with it.
  */
def ensureLocalStack(
    containerName: String,
    hostPort: Int,
    env: Map[String, String]): String =
  val endpoint = s"http://localhost:$hostPort"
  val token = localStackToken(env)
  val services =
    if token.isDefined then
      licensedServices
    else
      freeServices
  val image =
    if token.isDefined then
      s"localstack/localstack-pro:$localStackVersion"
    else
      s"localstack/localstack:$localStackVersion"
  // IAM is enforced only with a licence. Soft by default: `IAM_SOFT_MODE` logs each evaluation without
  // denying. Hard mode is not reachable: LocalStack does not enforce the ECS condition keys this policy uses.
  // `CAPTAL_IAM_SOFT=0` tries hard mode anyway.
  val soft =
    if env.get("CAPTAL_IAM_SOFT").contains("0") then
      "0"
    else
      "1"
  // One port inside and out: `LOCALSTACK_HOST` is written into every URL LocalStack returns, so it must
  // name the port it listens on. `GATEWAY_LISTEN` moves that listener off 4566.
  val localStackHost = s"localhost.localstack.cloud:$hostPort"
  val gatewayListen = s"0.0.0.0:$hostPort,0.0.0.0:443"

  // The token by name only: its value reaches the container from the process's environment, which
  // `Processes.ensureManager` sets. The Docker socket is needed for ECS: LocalStack runs each task as a
  // real container.
  val licensedArgs =
    if token.isDefined then
      Seq(
        "-e",
        "LOCALSTACK_AUTH_TOKEN",
        "-e",
        "ENFORCE_IAM=1",
        "-e",
        s"IAM_SOFT_MODE=$soft",
        "-v",
        "/var/run/docker.sock:/var/run/docker.sock")
    else
      Nil
  // In the foreground with `--rm`: stopping the process stops the container and removes it with its volume.
  val spec = Processes.Spec(
    command = Seq(
      "docker",
      "run",
      "--rm",
      "--name",
      containerName,
      "-p",
      s"$hostPort:$hostPort",
      "-e",
      s"GATEWAY_LISTEN=$gatewayListen",
      "-e",
      s"LOCALSTACK_HOST=$localStackHost",
      "-e",
      s"SERVICES=$services") ++ licensedArgs :+ image,
    ready = Some(s"curl -sf -m 1 $endpoint/_localstack/health > /dev/null")
  )

  val started = Processes.start(
    env,
    localStackProcess,
    spec,
    before = () => removeLocalStackContainers(containerName))
  val edition =
    if token.isDefined then
      "licensed"
    else
      "free tier"
  if started then
    println(s"[localstack] starting '$containerName' on port $hostPort ($edition)...")
  else
    println(s"[localstack] '$containerName' already running: $image, SERVICES=$services")
  Processes.await(localStackProcess, 120, s"starting LocalStack ($edition)")(localStackHealthy(endpoint))
  if started then
    // The state of every earlier container describes resources that went with it.
    val current = localStackContainerId()
    mill
      .api
      .BuildCtx
      .withFilesystemCheckerDisabled:
        if os.exists(localStackStates) then
          os.list(localStackStates)
            .filterNot(dir => current.contains(dir.last))
            .foreach(os.remove.all(_))

  // The health endpoint lists the services it registered; a missing one is named here, not later as a 501.
  val declared = services.split(',').toSet
  val health = os
    .proc("curl", "-sf", "-m", "5", s"$endpoint/_localstack/health")
    .call(check = false, stderr = os.Pipe)
    .out
    .text()
  val missing = declared.filterNot(service => health.contains(s"\"$service\""))
  if missing.nonEmpty then
    sys.error(
      s"[localstack] ${missing.toList.sorted.mkString(", ")} not in the registry. ${
          if token.isDefined then
            "A token was supplied, so either it is not valid for these services, the image does not carry them, or devenv's process manager was started without it (`devenv processes down`, then run again)"
          else
            "No LOCALSTACK_AUTH_TOKEN / LOCALSTACK_TOKEN was set, and these are licensed services"
        }.")

  println(s"[localstack] '$containerName' ready on $endpoint")
  endpoint
end ensureLocalStack

/** The OpenTofu configuration: `terraform/` in this repository, or the `CAPTAL_TERRAFORM_DIR` override. */
def terraformDir(env: Map[String, String], repoRoot: os.Path): os.Path = env
  .get("CAPTAL_TERRAFORM_DIR")
  .map(os.Path(_, repoRoot))
  .getOrElse(repoRoot / "terraform")

/** The OpenTofu state directory of one LocalStack container, shared by every apply against it. */
def localStackStateDir(containerId: String): os.Path = localStackStates / containerId

/** For a release to the LocalStack at `endpoint`: starts this build's container if needed, and answers
  * the state directory `ci.run`, `mcp.dev` and the local suite share. Needs the licence, since a release
  * runs ECS.
  */
def localStackWorkDir(endpoint: String, env: Map[String, String]): os.Path =
  val port = java.net.URI(endpoint).getPort
  if port != localStackPort then
    sys.error(
      s"[release] AWS_ENDPOINT_URL names port $port, and this build's LocalStack listens on $localStackPort")
  if localStackToken(env).isEmpty then
    sys.error(
      "[release] a release needs ECS, which LocalStack runs only with a licence. Set LOCALSTACK_AUTH_TOKEN in .env.")
  val _ = ensureLocalStack(localStackContainer, localStackPort, env)
  localStackStateDir(localStackContainerId().getOrElse(
    sys.error(s"[release] '$localStackContainer' is not running after starting it; `docker logs $localStackContainer` says why")))

/** The local load balancer's address, as LocalStack names it: after the load balancer, which
  * `modules/alb` names `<project>-<environment>`.
  */
val localLoadBalancerHost = "captal-local.elb.localhost.localstack.cloud"

/** Beside devenv's state rather than under `out/`, so that `mill clean` cannot drop a state whose resources
  * LocalStack still holds.
  */
def localStackStates: os.Path = Processes.state / "localstack" / "tofu"

/** The id of this build's LocalStack container, if it is running. */
def localStackContainerId(): Option[String] =
  Some(os.proc("docker", "ps", "-q", "-f", s"name=$localStackContainer").call().out.trim())
    .filter(_.nonEmpty)

/** Plans and applies the `local` environment of the OpenTofu configuration against LocalStack, and answers
  * its outputs as environment variables. Empty without a licence.
  *
  * The state in `stateDir` and the LocalStack container must be born together: if they diverge, the apply
  * fails with `AlreadyExists`, and [[resetLocalStack]] recreates both. Every run plans and applies, through
  * [[applyConfiguration]].
  */
def ensureLocalStackTofu(
    endpoint: String,
    licensed: Boolean,
    configDir: os.Path,
    stateDir: os.Path,
    rqliteImage: String,
    tags: ImageTags): Map[String, String] =
  // Unlicensed: no `ECS_CLUSTER`, so the deploy tools are not registered and the end-to-end test skips itself.
  if !licensed then
    println("[tofu] no LocalStack licence, so no infrastructure; the deploy end-to-end test will skip")
    Map.empty
  else

  if !os.exists(configDir) then
    sys.error(
      s"[tofu] no OpenTofu configuration at $configDir. Set CAPTAL_TERRAFORM_DIR if it lives elsewhere.")

  // Only rqlite's image is built here: building the others would slow every run. Their tags are the release
  // versions, so this apply, `mcp.dev` and `ci.run` agree on the task definitions.
  val target = ReleaseTarget.localStack(endpoint)
  val (outputs, _) = applyConfiguration(target, configDir, stateDir, tags): registries =>
    publishImages(
      target,
      configDir,
      registries,
      BuiltImages(Some(rqliteImage), None, None, None),
      tags)

  def output(name: String): String = outputs.getOrElse(
    name,
    sys.error(
      s"[tofu] the apply reported success but output '$name' is missing or empty. Outputs present: ${outputs.keys.toSeq.sorted.mkString(", ")}"))

  /** An output that is `null` in an environment that does not produce it. */
  def optionalOutput(name: String): Option[String] = outputs.get(name)

  println(
    s"[tofu] ready: cluster ${output("ecs_cluster_name")}, vpc ${output("vpc_id")}, distribution ${output("cloudfront_distribution_id")}")

  Map(
    "AWS_ENDPOINT_URL" -> endpoint,
    // Matches `aws_region` in the local tfvars; an input to the configuration, not an output of it.
    "AWS_REGION" -> "us-east-1",
    // The IAM user the configuration creates for `local`, with the deployed task role's policy: an
    // ungranted permission fails here under `ENFORCE_IAM`. The `test` fallback is always set, so no run
    // picks up a real profile.
    "AWS_ACCESS_KEY_ID" -> optionalOutput("mcp_test_user_access_key_id").getOrElse("test"),
    "AWS_SECRET_ACCESS_KEY" -> optionalOutput("mcp_test_user_secret_access_key").getOrElse("test"),
    // The configuration's bucket, which overrides the suite's fallback: under enforcement the policy
    // grants object actions on this bucket only.
    "ASSETS_BUCKET" -> output("assets_bucket_name"),
    "ECS_CLUSTER" -> output("ecs_cluster_name"),
    "VPC_ID" -> output("vpc_id"),
    "SUBNET_IDS" -> output("subnet_ids"),
    "SECURITY_GROUP_ID" -> output("ecs_tasks_security_group_id"),
    "ALB_LISTENER_ARN" -> output("alb_listener_arn"),
    "TASK_EXECUTION_ROLE_ARN" -> output("task_execution_role_arn"),
    "TASK_ROLE_ARN" -> output("task_role_arn"),
    // Only the id is used, by the invalidation: nothing fetches through the distribution.
    "CLOUDFRONT_DISTRIBUTION_ID" -> output("cloudfront_distribution_id"),
    // Not read by the server; `mcp.dev` builds `PUBLIC_BASE_URL` from it.
    "CLOUDFRONT_DOMAIN_NAME" -> output("cloudfront_domain_name"),
    "API_IMAGE_REPOSITORY_URL" -> output("api_image_repository_url"),
    "MCP_IMAGE_REPOSITORY_URL" -> output("mcp_image_repository_url"),
    "CDC_IMAGE_REPOSITORY_URL" -> output("cdc_image_repository_url"),
    "RQLITE_URL" -> output("rqlite_url"),
    // An image already on the machine: LocalStack runs tasks as real containers, and would retry a pull that cannot succeed.
    "API_IMAGE" -> s"rqlite/rqlite:$rqliteVersion"
  )
end ensureLocalStackTofu

