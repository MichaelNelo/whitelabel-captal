package millbuild

/** The Redis image of the suite's lock instance: the one `modules/redis-lock` runs by default. */
val redisImage = "public.ecr.aws/docker/library/redis:7.4.1-alpine"

/** The local configuration's lock instance, as the host reaches it, at its `redis_lock_port`: `api.dev`
  * takes the migrations' lock there, the one the backoffice and the locations in LocalStack take.
  */
def localRedisAddress(configDir: os.Path): String =
  val file = configDir / "environments" / "local" / "terraform.tfvars"
  val port = """(?m)^\s*redis_lock_port\s*=\s*(\d+)""".r
    .findFirstMatchIn(os.read(file))
    .map(_.group(1))
    .getOrElse(sys.error(s"[redis] $file sets no redis_lock_port"))
  s"localhost:$port"

/** Runs a test suite's Redis as the devenv process `name`, a container of the same name on `hostPort`,
  * unless it already runs, and returns its `host:port`. In memory only, as `modules/redis-lock` runs
  * it: its locks go with the container.
  */
def ensureRedis(name: String, hostPort: Int, env: Map[String, String]): String =
  def pong: Boolean = os
    .proc("docker", "exec", name, "redis-cli", "ping")
    .call(check = false, stderr = os.Pipe)
    .out
    .trim() == "PONG"
  val spec = Processes.Spec(
    command = Seq(
      "docker",
      "run",
      "--rm",
      "--name",
      name,
      "-p",
      s"$hostPort:6379",
      redisImage,
      "redis-server",
      "--save",
      "",
      "--appendonly",
      "no"),
    ready = Some(s"docker exec $name redis-cli ping | grep -q PONG")
  )
  // A container left by a manager that did not stop cleanly, and anything else on the port.
  def clear(): Unit =
    val _ = os.proc("docker", "rm", "-f", name).call(check = false, stderr = os.Pipe)
    val holder = os.proc("docker", "ps", "-q", "-f", s"publish=$hostPort").call().out.trim()
    if holder.nonEmpty then
      sys.error(s"[redis] port $hostPort is already in use by container $holder")
  if Processes.start(env, name, spec, before = () => clear()) then
    println(s"[redis] starting '$name' on port $hostPort...")
  Processes.await(name, 30, s"starting Redis on $hostPort")(pong)
  println(s"[redis] '$name' ready on port $hostPort")
  s"localhost:$hostPort"
end ensureRedis
