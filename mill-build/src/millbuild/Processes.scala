package millbuild

/** The long-running processes `devenv.nix` declares, run by devenv's process manager and started,
  * restarted and stopped from here.
  *
  * Each process runs what [[start]] writes under `.devenv/state/processes/<name>/`: `command`, one
  * argument per line; `env`, one `KEY=VALUE` per line; and `ready`, a bash snippet that exits 0 once
  * the process serves. The manager runs apart from the Mill daemon and its processes inherit the
  * environment of the invocation that started it.
  */
object Processes:

  /** What a process runs: its command line, the variables added to its environment, and its
    * readiness check.
    */
  final case class Spec(
      command: Seq[String],
      env: Map[String, String] = Map.empty,
      ready: Option[String] = None)

  /** devenv's state directory for this workspace, `$DEVENV_STATE`. */
  def state: os.Path = mill.api.BuildCtx.workspaceRoot / ".devenv" / "state"

  private def dir(name: String): os.Path = state / "processes" / name

  private def devenv(args: String*): os.CommandResult = os
    .proc("devenv", args)
    .call(
      cwd = mill.api.BuildCtx.workspaceRoot,
      check = false,
      stdout = os.Pipe,
      stderr = os.Pipe)

  /** Whether devenv's process manager is running for this workspace. */
  def managerRunning: Boolean = devenv("processes", "list").exitCode == 0

  /** Starts the manager, running nothing yet, unless it is running already. Its processes inherit
    * `env`, with the LocalStack licence also under `LOCALSTACK_AUTH_TOKEN`, the name the LocalStack
    * image reads.
    */
  def ensureManager(env: Map[String, String]): Unit =
    if !managerRunning then
      println("[processes] starting devenv's process manager")
      val up = os
        .proc("devenv", "up", "-d")
        .call(
          cwd = mill.api.BuildCtx.workspaceRoot,
          env = env ++ localStackToken(env).map("LOCALSTACK_AUTH_TOKEN" -> _),
          check = false,
          stdout = os.Pipe,
          stderr = os.Pipe)
      if up.exitCode != 0 || !managerRunning then
        sys.error(s"[processes] `devenv up -d` did not start the manager:\n${up.err.text().trim()}")

  private def pidFile(name: String): os.Path = dir(name) / "pid"

  /** Whether `name` is running: the pid its launcher recorded is alive. Not devenv's reported phase,
    * which keeps saying `exited` after a restart until the process first passes its readiness check.
    */
  def running(name: String): Boolean = Option
    .when(os.exists(pidFile(name)))(os.read(pidFile(name)).trim)
    .flatMap(_.toLongOption)
    .exists(pid => ProcessHandle.of(pid).filter(_.isAlive).isPresent)

  /** Writes `spec` as the files `name` runs from. Answers whether what it runs — its command or its
    * environment — changed; a new readiness check alone does not need a restart. The `env` file is
    * readable by the owner only.
    */
  private def write(name: String, spec: Spec): Boolean =
    val newline = (spec.command ++ spec.env.flatMap((k, v) => Seq(k, v))).find(_.contains('\n'))
    newline.foreach(value =>
      sys.error(s"[processes] $name: '$value' holds a newline, which its files cannot carry"))
    def lines(values: Seq[String]) = values.map(_ + "\n").mkString
    val files = Seq(
      ("command", Some(lines(spec.command)), "rw-r--r--", true),
      (
        "env",
        Option.when(spec.env.nonEmpty)(lines(spec.env.toSeq.sorted.map((k, v) => s"$k=$v"))),
        "rw-------",
        true),
      ("ready", spec.ready.map(r => lines(Seq(r))), "rw-r--r--", false)
    )
    mill
      .api
      .BuildCtx
      .withFilesystemCheckerDisabled:
        os.makeDir.all(dir(name))
        files
          .map: (file, content, perms, restarts) =>
            val path = dir(name) / file
            val before = Option.when(os.exists(path))(os.read(path))
            content match
              case Some(text) =>
                os.write.over(path, text, perms = os.PermSet.fromString(perms))
              case None =>
                os.remove(path, checkExists = false)
            restarts && before != content
          .exists(identity)
  end write

  /** Runs `name` with `spec`: starts it when it is not running, and restarts it when it runs
    * anything else — or, with `always`, regardless. `before` runs just before a (re)start. Answers
    * whether it (re)started.
    */
  def start(
      env: Map[String, String],
      name: String,
      spec: Spec,
      always: Boolean = false,
      before: () => Unit = () => ()): Boolean =
    ensureManager(env)
    val changed = write(name, spec)
    val restart = always || changed || !running(name)
    if restart then
      before()
      mill.api.BuildCtx.withFilesystemCheckerDisabled(os.remove(pidFile(name), checkExists = false))
      val result = devenv("processes", "restart", name)
      if result.exitCode != 0 then
        sys.error(s"[processes] could not start $name:\n${result.err.text().trim()}")
      // The launcher records the pid before it execs, so a process that dies at once still leaves one.
      val deadline = System.nanoTime + 15_000_000_000L
      while !os.exists(pidFile(name)) && System.nanoTime < deadline do
        Thread.sleep(200)
      if !os.exists(pidFile(name)) then
        sys.error(
          s"[processes] $name did not start. If `devenv.nix` changed since the manager started, `devenv processes down` and run again.\n${logs(name)}")
    restart

  /** Stops `name` if it is running, and waits for it to end. */
  def stop(name: String): Unit =
    if running(name) then
      val _ = devenv("processes", "stop", name)
      val deadline = System.nanoTime + 30_000_000_000L
      while running(name) && System.nanoTime < deadline do
        Thread.sleep(200)
      println(s"[processes] stopped $name")

  /** The last `count` lines `name` printed. */
  def logs(name: String, count: Int = 30): String =
    devenv("processes", "logs", name).out.lines().takeRight(count).mkString("\n")

  /** Waits up to `seconds` for `ready`, failing at once, with the process's log, if `name` ends. */
  def await(name: String, seconds: Int, what: String)(ready: => Boolean): Unit =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    var done = ready
    while !done && System.nanoTime < deadline do
      if !running(name) then
        sys.error(s"[$name] $what: the process exited.\n${logs(name)}")
      Thread.sleep(500)
      done = ready
    if !done then
      sys.error(s"[$name] $what: not ready after ${seconds}s.\n${logs(name)}")

  /** Stops every process, one by one, then the manager. Not the manager alone: `devenv processes down`
    * leaves the containers of some processes running, outside its control.
    */
  def down(): Unit =
    if managerRunning then
      if os.exists(state / "processes") then
        os.list(state / "processes").map(_.last).foreach(stop)
      val _ = devenv("processes", "down")
      println("[processes] stopped devenv's process manager and its processes")
end Processes

/** A `ready` snippet that succeeds once something listens on `port` locally. */
def portOpen(port: Int): String = s"timeout 1 bash -c 'echo > /dev/tcp/127.0.0.1/$port' 2>/dev/null"

/** Whether something listens on `port` locally. Bounded: a connection to a port nobody listens on can
  * hang instead of being refused.
  */
def listening(port: Int): Boolean = scala
  .util
  .Try:
    val socket = java.net.Socket()
    try socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
    finally socket.close()
  .isSuccess

/** Whether `url` answers 2xx within two seconds. */
def answers(url: String): Boolean =
  scala.util.Try(os.proc("curl", "-sf", "-m", "2", "-o", "/dev/null", url).call()).isSuccess
