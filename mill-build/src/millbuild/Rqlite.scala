package millbuild

/** The rqlite engine version of every suite container and of the end-to-end task definition. Production runs
  * this version: moving it means moving the driver too.
  */
val rqliteVersion = "10.6.1"

/** Runs a test suite's rqlite as the devenv process `name`, a container of the same name on `hostPort`,
  * unless it already runs, and returns its JDBC URL. Its data goes with the container.
  */
def ensureRqlite(name: String, hostPort: Int, env: Map[String, String]): String =
  val url = s"http://localhost:$hostPort"
  val spec = Processes.Spec(
    command = Seq(
      "docker",
      "run",
      "--rm",
      "--name",
      name,
      "-p",
      s"$hostPort:4001",
      "-e",
      "SQLITE_EXTENSIONS=sqlean,misc",
      s"rqlite/rqlite:$rqliteVersion"),
    ready = Some(s"curl -sf -m 1 $url/readyz > /dev/null")
  )
  // A container left by a manager that did not stop cleanly, and anything else on the port.
  def clear(): Unit =
    val _ = os.proc("docker", "rm", "-f", name).call(check = false, stderr = os.Pipe)
    val holder = os.proc("docker", "ps", "-q", "-f", s"publish=$hostPort").call().out.trim()
    if holder.nonEmpty then
      sys.error(s"[rqlite] port $hostPort is already in use by container $holder")
  if Processes.start(env, name, spec, before = () => clear()) then
    println(s"[rqlite] starting '$name' on port $hostPort...")
  Processes.await(name, 30, s"starting rqlite on $hostPort")(answers(s"$url/readyz"))
  println(s"[rqlite] '$name' ready on port $hostPort")
  s"jdbc:rqlite:$url"
end ensureRqlite

/** The local configuration's rqlite, as this machine reaches it, at its `rqlite_http_port`. `api.dev` and
  * `mcp.dev` must share this database. Deploy tasks use the `rqlite_url` output instead.
  */
def localDbUrl(configDir: os.Path): String =
  val file = configDir / "environments" / "local" / "terraform.tfvars"
  val port = """(?m)^\s*rqlite_http_port\s*=\s*(\d+)""".r
    .findFirstMatchIn(os.read(file))
    .map(_.group(1))
    .getOrElse(sys.error(s"[rqlite] $file sets no rqlite_http_port"))
  s"jdbc:rqlite:http://localhost:$port"

/** Waits up to three minutes for that rqlite to report ready (a leader and a store). Fails with a hint to
  * start `mcp.dev`, which applies the local configuration that creates it.
  */
def awaitLocalDb(dbUrl: String): Unit =
  val ready = dbUrl.stripPrefix("jdbc:rqlite:") + "/readyz"
  val deadline = System.nanoTime + 180_000_000_000L
  def answers: Boolean = scala
    .util
    .Try:
      val connection = java.net.URI.create(ready).toURL.openConnection()
      connection.setConnectTimeout(2000)
      connection.setReadTimeout(2000)
      connection.asInstanceOf[java.net.HttpURLConnection].getResponseCode == 200
    .getOrElse(false)
  while !answers && System.nanoTime < deadline do
    Thread.sleep(2000)
  if !answers then
    sys.error(
      s"[rqlite] nothing ready at $ready after three minutes. It is the local configuration's rqlite service, which `mill mcp.dev` applies; start that first.")
  println(s"[rqlite] ready at ${dbUrl.stripPrefix("jdbc:rqlite:")}")

/** The rqlite image tag that `environments/<env>/terraform.tfvars` names. The same value the task definition pulls.
  */
def rqliteImageTag(configDir: os.Path, environment: String): String =
  val file = configDir / "environments" / environment / "terraform.tfvars"
  """(?m)^\s*rqlite_image_tag\s*=\s*"([^"]+)"""".r
    .findFirstMatchIn(os.read(file))
    .map(_.group(1))
    .getOrElse(sys.error(s"[image] $file sets no rqlite_image_tag, so there is no tag to push"))
