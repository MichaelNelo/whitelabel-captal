package millbuild

/** `docker tag` then `docker push`, as one step that names which half failed. */
def pushImage(local: String, remote: String): String =
  for args <- Seq(Seq("tag", local, remote), Seq("push", remote))
  do
    val result = os.proc("docker" +: args).call(check = false, stderr = os.Pipe)
    if result.exitCode != 0 then
      sys.error(s"[image] docker ${args.head} to $remote failed: ${result.err.trim()}")
  println(s"[image] pushed $remote")
  remote

// ─────────────────────────────────────────────────────────────────────────────
// Release: what `ci.run` does in an AWS account, and `mcp.dev` against LocalStack
// ─────────────────────────────────────────────────────────────────────────────

/** Where the release operations act, read from the environment: CodeBuild's for `ci.run`, and LocalStack's for `mcp.dev`.
  *
  * @param environment
  *   `CAPTAL_ENV`: the `terraform/environments/<name>` whose tfvars, and outside LocalStack whose
  *   backend, the configuration is applied with.
  * @param endpoint
  *   `AWS_ENDPOINT_URL`. Set, it is this build's LocalStack: every AWS call goes through `awslocal`
  *   inside its container, which needs no credentials, and the state is the one `mcp.dev` applies
  *   with. Unset, it is the account the credentials name.
  */
final case class ReleaseTarget(environment: String, region: String, endpoint: Option[String]):
  def local: Boolean = endpoint.isDefined

object ReleaseTarget:
  def fromEnv(env: Map[String, String]): ReleaseTarget =
    def value(name: String) = env.get(name).map(_.trim).filter(_.nonEmpty)
    ReleaseTarget(
      environment = value("CAPTAL_ENV").getOrElse(
        sys.error("[release] CAPTAL_ENV is not set: name the terraform/environments/<name> to release to")),
      region = value("AWS_REGION").orElse(value("AWS_DEFAULT_REGION")).getOrElse("us-east-1"),
      endpoint = value("AWS_ENDPOINT_URL")
    )

  /** The target `mcp.dev` and the local suite release to: the `local` environment on LocalStack at `endpoint`.
    */
  def localStack(endpoint: String): ReleaseTarget = fromEnv(
    Map("CAPTAL_ENV" -> "local", "AWS_REGION" -> "us-east-1", "AWS_ENDPOINT_URL" -> endpoint))
end ReleaseTarget

/** The tags the API, backoffice and relay images go up under, which the configuration is applied with
  * as `image_tag`, `mcp_image_tag` and `cdc_image_tag`, and pushes each image under when its tag
  * changes: their [[ReleaseVersion.version]]s, or [[ImageTags.local]] ones.
  */
final case class ImageTags(api: String, mcp: String, cdc: String)

object ImageTags:
  /** A local build's tag: the version, and a hash of what goes into the image. Locally the version
    * moves only with the commits that release, while every change to the code has to reach LocalStack;
    * the same inputs give the same tag, so a build that changed nothing pushes nothing.
    */
  def local(version: String, inputs: mill.api.PathRef*): String =
    f"$version-local.${inputs.map(_.sig).hashCode}%08x"

/** One AWS CLI call: `awslocal` inside LocalStack's container for a local target, `aws` otherwise. */
def awsCall(target: ReleaseTarget, args: String*): os.CommandResult =
  val command =
    if target.local then
      Seq("docker", "exec", localStackContainer, "awslocal") ++ args
    else
      Seq("aws", "--region", target.region) ++ args
  os.proc(command).call(check = false, stderr = os.Pipe)

/** As [[awsCall]], failing with what AWS said, and its answer parsed. */
def awsJson(target: ReleaseTarget, args: String*): ujson.Value =
  val result = awsCall(target, (args ++ Seq("--output", "json"))*)
  if result.exitCode != 0 then
    sys.error(s"[release] aws ${args.take(2).mkString(" ")} failed: ${result.err.trim()}")
  val text = result.out.text().trim
  if text.isEmpty then
    ujson.Null
  else
    ujson.read(text)

/** As [[awsCall]] with `--output text`, `None` for an answer AWS refused or left empty. */
def awsText(target: ReleaseTarget, args: String*): Option[String] =
  val result = awsCall(target, (args ++ Seq("--output", "text"))*)
  Option
    .when(result.exitCode == 0)(result.out.text().trim)
    .filter(text => text.nonEmpty && text != "None")

/** Applies the configuration to the target: `init`, a plan of everything and its apply. Answers the
  * string outputs.
  *
  * The images have to be built first, as `captal-<name>:latest`: the apply pushes each one whose tag
  * changed before any service is pointed at it (`terraform/modules/images`). The configuration is
  * staged into `workDir`, so `terraform/` stays untouched. Locally `backend.tf` is omitted (its bucket
  * is created by this configuration), and the state lives in `workDir`. `extraVariables` are `-var`
  * arguments added to the environment's tfvars and the image tags.
  */
def applyConfiguration(
    target: ReleaseTarget,
    configDir: os.Path,
    workDir: os.Path,
    tags: ImageTags,
    extraVariables: Seq[String] = Nil): Map[String, String] =
  val staged = workDir / "config"
  val state = workDir / "terraform.tfstate"
  val plan = workDir / "release.tfplan"

  os.remove.all(staged)
  os.makeDir.all(staged)
  os.list(configDir)
    .filter(p => p.last.endsWith(".tf") && !(target.local && p.last == "backend.tf"))
    .foreach(p => os.copy.into(p, staged))
  Seq("modules", "environments").foreach(dir => os.copy(configDir / dir, staged / dir))
  val lock = configDir / ".terraform.lock.hcl"
  if os.exists(lock) then
    os.copy.into(lock, staged)

  // `-chdir=` must be one argument: tofu rejects the separated form. Uncoloured, since the output is parsed.
  def tofu(args: String*): os.CommandResult = os
    .proc("tofu", s"-chdir=$staged", args)
    .call(
      env = Map(
        "TF_DATA_DIR"      -> (workDir / ".terraform").toString,
        "TF_IN_AUTOMATION" -> "1",
        "TF_CLI_ARGS"      -> "-no-color"),
      check = false,
      stdout = os.Pipe,
      stderr = os.Pipe)

  def fail(what: String, result: os.CommandResult): Nothing =
    val output = (result.out.text() + result.err.text()).trim()
    // Locally, AlreadyExists means LocalStack holds resources this state does not know about.
    val diverged = target.local &&
      (output.contains("AlreadyExists") || output.contains("EntityAlreadyExists"))
    sys.error(
      s"[tofu] $what failed:\n${output.linesIterator.toSeq.takeRight(40).mkString("\n")}" + (
        if diverged then
          s"\n\nThat is LocalStack holding resources the state in $workDir does not know about — the two have come apart. Run `mill mcp.test.resetLocalStack` to recreate the container with an empty state, and let the next run build both together."
        else
          ""
      ))

  val stateArgs = if target.local then Seq(s"-state=$state") else Nil
  val variables =
    Seq(s"-var-file=environments/${target.environment}/terraform.tfvars") ++
      target.endpoint.map(endpoint => s"-var=aws_endpoint_url=$endpoint") ++
      Seq(
        s"-var=image_tag=${tags.api}",
        s"-var=mcp_image_tag=${tags.mcp}",
        s"-var=cdc_image_tag=${tags.cdc}") ++
      extraVariables ++
      stateArgs

  def outputs(): Map[String, String] =
    val result = tofu(Seq("output", "-json") ++ stateArgs*)
    if result.exitCode != 0 then
      fail("output", result)
    ujson
      .read(result.out.text())
      .obj
      .collect { case (name, output) if output.obj("value").strOpt.exists(_.nonEmpty) =>
        name -> output.obj("value").str
      }
      .toMap

  val backend =
    if target.local then
      Nil
    else
      Seq(s"-backend-config=environments/${target.environment}/backend.tfvars")
  val initialised = tofu(Seq("init", "-input=false") ++ backend*)
  if initialised.exitCode != 0 then
    fail("init", initialised)

  // The images go up inside the apply, so its output carries the pushes' lines.
  println(s"[tofu] planning the ${target.environment} environment (minutes, on a fresh LocalStack)")
  val planned = tofu(Seq("plan", "-input=false", s"-out=$plan") ++ variables*)
  if planned.exitCode != 0 then
    fail("plan", planned)
  planned.out.lines().filter(_.startsWith("Plan:")).foreach(line => println(s"[tofu] $line"))
  val applied = tofu(Seq("apply", "-input=false") ++ stateArgs ++ Seq(plan.toString)*)
  if applied.exitCode != 0 then
    fail("apply", applied)
  // What a push printed, not tofu's echo of the command, which contains the same words.
  applied
    .out
    .lines()
    .flatMap(line => "\\(local-exec\\): (\\[image\\] .*)".r.findFirstMatchIn(line).map(_.group(1)))
    .foreach(println)
  outputs()
end applyConfiguration

/** `ci.run`'s first four operations, which `mcp.dev` runs against LocalStack: applies the configuration,
  * which pushes the images whose tags changed and points the services at them, moves every location onto
  * the API image, and publishes the client. The images have to be built first. Answers the
  * configuration's outputs.
  *
  * `variables` are passed to the plan beside the environment's tfvars. With `awaitLocations`, it waits for
  * the locations to settle and fails naming the ones that did not.
  */
def release(
    target: ReleaseTarget,
    configDir: os.Path,
    workDir: os.Path,
    tags: ImageTags,
    bundle: os.Path,
    clientVersion: String,
    variables: Seq[String],
    awaitLocations: Boolean): Map[String, String] =
  println(s"[release] Applying the ${target.environment} configuration, which pushes the images...")
  val outputs = applyConfiguration(target, configDir, workDir, tags, variables)
  def output(name: String) = outputs.getOrElse(
    name,
    sys.error(s"[release] the configuration has no $name output"))
  val cluster = output("ecs_cluster_name")

  println("[release] Moving every location onto the API image...")
  val locations = locationServices(target, cluster)
  rollLocations(target, cluster, locations, output("api_image"), await = awaitLocations)

  // Published once per version: skipped when the bucket holds it already. Locally it is always published.
  val bucket = output("assets_bucket_name")
  if !target.local && clientPublished(target, bucket, clientVersion) then
    println(s"[release] The client's $clientVersion is published already")
  else
    println(s"[release] Publishing the client's $clientVersion to every location...")
    publishClient(
      target,
      bundle,
      clientVersion,
      bucket,
      output("cloudfront_distribution_id"),
      locations)
  outputs
end release

/** Leaves `service`'s target group holding its running tasks and nothing else. For LocalStack, which
  * registers a task that starts and never deregisters one that stops, while its load balancer sends
  * every request to the first target it holds: once a task is replaced, every request goes to one
  * that is gone, and fails.
  */
def syncLocalTargets(target: ReleaseTarget, cluster: String, service: String): Unit =
  val described =
    awsJson(target, "ecs", "describe-services", "--cluster", cluster, "--services", service)(
      "services")(0)
  described("loadBalancers")
    .arr
    .headOption
    .foreach: balanced =>
      val group = balanced("targetGroupArn").str
      val port = balanced("containerPort").num.toInt
      val running = runningTasks(target, cluster, service)
        .flatMap(_("containers")(0)("networkInterfaces").arr.headOption)
        .map(interface => interface("privateIpv4Address").str -> port)
        .toSet
      val registered = awsJson(target, "elbv2", "describe-target-health", "--target-group-arn", group)(
        "TargetHealthDescriptions")
        .arr
        .map(health => health("Target")("Id").str -> health("Target")("Port").num.toInt)
        .toSet
      def targets(of: Set[(String, Int)]) = of.toSeq.map((id, port) => s"Id=$id,Port=$port")
      val stale = registered -- running
      val missing = running -- registered
      if stale.nonEmpty then
        val _ = awsJson(
          target,
          (Seq("elbv2", "deregister-targets", "--target-group-arn", group, "--targets") ++
            targets(stale))*)
        println(s"[release] $service: deregistered ${stale.size} target(s) of tasks that are gone")
      if missing.nonEmpty then
        val _ = awsJson(
          target,
          (Seq("elbv2", "register-targets", "--target-group-arn", group, "--targets") ++
            targets(missing))*)
end syncLocalTargets

/** `service`'s tasks that are running, as ECS describes them. */
def runningTasks(target: ReleaseTarget, cluster: String, service: String): Seq[ujson.Value] =
  val arns = awsJson(
    target,
    "ecs",
    "list-tasks",
    "--cluster",
    cluster,
    "--service-name",
    service,
    "--desired-status",
    "RUNNING")("taskArns").arr.map(_.str).toSeq
  if arns.isEmpty then
    Nil
  else
    awsJson(target, (Seq("ecs", "describe-tasks", "--cluster", cluster, "--tasks") ++ arns)*)(
      "tasks").arr.filter(_("lastStatus").str == "RUNNING").toSeq

/** Whether `service` runs as many tasks as it wants, all on its current task definition and none of
  * `replaced`, the tasks it ran before it was told to start fresh ones on the same definition. Once it
  * does, its target group is left holding them alone ([[syncLocalTargets]]). Tasks are told apart by
  * their ARNs rather than by when they started: LocalStack's clock is its VM's, not this machine's.
  */
def settledLocally(
    target: ReleaseTarget,
    cluster: String,
    service: String,
    replaced: Set[String]): Boolean =
  val described =
    awsJson(target, "ecs", "describe-services", "--cluster", cluster, "--services", service)(
      "services")(0)
  val running = runningTasks(target, cluster, service)
  val settled = running.size == described("desiredCount").num.toInt &&
    running.forall(task =>
      task("taskDefinitionArn").str == described("taskDefinition").str &&
        !replaced.contains(task("taskArn").str))
  if settled then
    syncLocalTargets(target, cluster, service)
  settled

/** A location's service as ECS reports it, with the task definition it runs. */
final case class LocationService(
    name: String,
    slug: String,
    taskDefinition: ujson.Value,
    desiredCount: Int):
  def image: String = taskDefinition("containerDefinitions")(0)("image").str
  def paused: Boolean = desiredCount == 0

/** Every location's service in the cluster: the ones whose task runs a single container named
  * `api`, which is how `deploy_location` registers them, as opposed to the configuration's own
  * `mcp` and `rqlite`. A destroyed location has no service and is not here.
  */
def locationServices(target: ReleaseTarget, cluster: String): Seq[LocationService] =
  val arns = awsJson(target, "ecs", "list-services", "--cluster", cluster)("serviceArns")
    .arr
    .map(_.str)
    .toSeq
  arns
    .grouped(10)
    .flatMap: batch =>
      awsJson(
        target,
        Seq("ecs", "describe-services", "--cluster", cluster, "--services") ++ batch*)("services")
        .arr
        .filter(_("status").str == "ACTIVE")
        .flatMap: service =>
          val definition = awsJson(
            target,
            "ecs",
            "describe-task-definition",
            "--task-definition",
            service("taskDefinition").str)("taskDefinition")
          val containers = definition("containerDefinitions").arr
          Option.when(containers.size == 1 && containers(0)("name").str == "api"):
            val name = service("serviceName").str
            LocationService(name, name.stripPrefix("captal-"), definition, service("desiredCount").num.toInt)
    .toSeq
end locationServices

/** What `register-task-definition` takes back out of what `describe-task-definition` gave. */
val RegisterableFields = Seq(
  "family",
  "taskRoleArn",
  "executionRoleArn",
  "networkMode",
  "containerDefinitions",
  "volumes",
  "placementConstraints",
  "requiresCompatibilities",
  "cpu",
  "memory",
  "pidMode",
  "ipcMode",
  "proxyConfiguration",
  "inferenceAccelerators",
  "ephemeralStorage",
  "runtimePlatform"
)

/** Moves every location onto `api`, the API image by digest: a new task definition revision that differs only
  * in the image, and its service pointed at it. The service keeps its task count, so a paused location stays
  * paused and runs the new image on resume. A location already on that digest is left as it is.
  *
  * With `await`, waits for the moved services to settle and fails naming the ones that did not.
  */
def rollLocations(
    target: ReleaseTarget,
    cluster: String,
    locations: Seq[LocationService],
    api: String,
    await: Boolean): Unit =
  val moved = locations.flatMap: location =>
    if location.image != api then
      val input = ujson.Obj.from(RegisterableFields.flatMap(field =>
        location.taskDefinition.obj.get(field).filterNot(_.isNull).map(field -> _)))
      input("containerDefinitions")(0)("image") = api
      val registered = awsJson(
        target,
        "ecs",
        "register-task-definition",
        "--cli-input-json",
        ujson.write(input))("taskDefinition")("taskDefinitionArn").str
      awsJson(
        target,
        "ecs",
        "update-service",
        "--cluster",
        cluster,
        "--service",
        location.name,
        "--task-definition",
        registered)
      println(s"[release] ${location.slug} -> ${registered.split('/').last}")
      Some(location.name)
    else
      None
  if moved.isEmpty then
    println(s"[release] ${locations.size} location(s), none to move")
  else if await then
    // In batches of ten: the most one `services-stable` wait takes.
    moved
      .grouped(10)
      .foreach: batch =>
        val settled = awsCall(
          target,
          Seq("ecs", "wait", "services-stable", "--cluster", cluster, "--services") ++ batch*)
        if settled.exitCode != 0 then
          sys.error(
            s"[release] did not settle on the new image: ${batch.mkString(", ")}. Their tasks' log groups say why.")
    println(s"[release] ${moved.size} location(s) settled on ${api.split('/').last}")
end rollLocations

/** Where a client release is kept: its bundle as published. */
def clientRelease(version: String): String = s"releases/client/$version"

/** Whether `version` of the client is in the bucket already, by its archived `index.html`. */
def clientPublished(target: ReleaseTarget, bucket: String, version: String): Boolean =
  awsText(
    target,
    "s3api",
    "head-object",
    "--bucket",
    bucket,
    "--key",
    s"${clientRelease(version)}/index.html",
    "--query",
    "ETag").isDefined

/** Publishes `version` of the client and gives every location its copy: the bundle under `bundle/` and
  * [[clientRelease]], the MCP App scripts under `ui/`, then one invalidation of everything.
  *
  * Each location gets `bundle/` as `deploy_location` copies it, except `maintenance.html`, and except its own
  * `brand-icon.svg` when its ETag differs from the bundle's. A paused location's `index.html` is kept aside as
  * `_paused/<slug>/site.html`, which resume restores.
  */
def publishClient(
    target: ReleaseTarget,
    bundle: os.Path,
    version: String,
    bucket: String,
    distributionId: String,
    locations: Seq[LocationService]): Unit =
  def etag(key: String): Option[String] = awsText(
    target,
    "s3api",
    "head-object",
    "--bucket",
    bucket,
    "--key",
    key,
    "--query",
    "ETag")
  def copy(args: String*): Unit =
    val result = awsCall(target, (Seq("s3", "cp") ++ args :+ "--only-show-errors")*)
    if result.exitCode != 0 then
      sys.error(s"[release] s3 cp ${args.take(2).mkString(" ")} failed: ${result.err.trim()}")

  // Published under its release, then copied to `bundle/`: rolling back is copying an earlier release.
  publishBundle(bundle, bucket, clientRelease(version), target.region, target.local)
  copy(s"s3://$bucket/${clientRelease(version)}/", s"s3://$bucket/bundle/", "--recursive")
  publishUiScripts(bundle, bucket, target.region, target.local)

  val bundleIcon = etag("bundle/brand-icon.svg")
  locations.foreach: location =>
    val ownIcon = etag(s"${location.slug}/brand-icon.svg")
    val excluded =
      Seq("maintenance.html") ++
        Option.when(ownIcon.isDefined && bundleIcon.isDefined && ownIcon != bundleIcon)(
          "brand-icon.svg") ++
        Option.when(location.paused)("index.html")
    copy(
      Seq(s"s3://$bucket/bundle/", s"s3://$bucket/${location.slug}/", "--recursive") ++
        excluded.flatMap(name => Seq("--exclude", name))*)
    if location.paused then
      copy(s"s3://$bucket/bundle/index.html", s"s3://$bucket/_paused/${location.slug}/site.html")
    println(s"[release] ${location.slug} has the new client${
        if location.paused then
          " (paused: kept for its resume)"
        else
          ""
      }")
  if locations.nonEmpty then
    val _ = awsJson(
      target,
      "cloudfront",
      "create-invalidation",
      "--distribution-id",
      distributionId,
      "--paths",
      "/*")
    println(s"[release] invalidated $distributionId")
end publishClient
