package millbuild

/** A Cloudflare quick tunnel to a local port, run as the devenv process `name` and reused while it
  * runs, so its address stays the same across runs. Answers its `https://….trycloudflare.com` address,
  * which cloudflared publishes on its metrics endpoint at `metricsPort`.
  */
def quickTunnel(env: Map[String, String], name: String, port: Int, metricsPort: Int): String =
  val metrics = s"http://127.0.0.1:$metricsPort/quicktunnel"
  val spec = Processes.Spec(
    command = Seq(
      "cloudflared",
      "tunnel",
      "--no-autoupdate",
      "--metrics",
      s"127.0.0.1:$metricsPort",
      "--url",
      s"http://localhost:$port"),
    ready = Some(s"curl -sf -m 1 $metrics | grep -q trycloudflare.com")
  )
  val _ = Processes.start(env, name, spec)
  var host = Option.empty[String]
  Processes.await(name, 60, s"opening a tunnel to :$port"):
    host = quickTunnelHost(metrics)
    host.isDefined
  s"https://${host.get}"

/** The hostname cloudflared reports for its quick tunnel, once it has one. */
private def quickTunnelHost(metrics: String): Option[String] = scala
  .util
  .Try(os.proc("curl", "-sf", "-m", "2", metrics).call().out.text())
  .toOption
  .flatMap(""""hostname":"([a-z0-9-]+\.trycloudflare\.com)"""".r.findFirstMatchIn(_))
  .map(_.group(1))

/** The devenv processes `mcp.dev --tunnel` opens: the backoffice's tunnel and LocalStack's. */
val tunnelProcesses = Seq("tunnel-mcp", "tunnel-localstack")
