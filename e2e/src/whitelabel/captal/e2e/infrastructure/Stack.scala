package whitelabel.captal.e2e.infrastructure

import whitelabel.captal.e2e.scenario.Run

/** The stack the features run against, as an operator of the infrastructure reaches it: the AWS CLI
  * against LocalStack, as the build reaches it, `awslocal` inside LocalStack's own container. For
  * what no tool of the backoffice does, such as restarting a service.
  */
object Stack:
  def cluster: String = Run.setting("E2E_CLUSTER")

  /** The change data capture relay's ECS service. */
  def relayService: String = Run.setting("E2E_CDC_SERVICE")

  /** `aws <args>` against LocalStack, answering what it printed; fails when it fails. */
  def aws(args: String*): String =
    val command =
      Seq("docker", "exec", Run.setting("E2E_LOCALSTACK_CONTAINER"), "awslocal") ++ args ++
        Seq("--output", "json")
    val started = ProcessBuilder(command*).redirectErrorStream(true).start()
    val out = String(started.getInputStream.readAllBytes())
    if started.waitFor() != 0 then
      throw AssertionError(s"`aws ${args.mkString(" ")}` failed: $out")
    out
  end aws

  /** Replaces every task of `service` with a new one, as a restart does. */
  def restart(service: String): Unit = aws(
    "ecs",
    "update-service",
    "--cluster",
    cluster,
    "--service",
    service,
    "--force-new-deployment")
end Stack
